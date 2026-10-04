const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const childProcess = require('node:child_process');
const { spawn, spawnSync } = childProcess;
const Module = require('node:module');
const jpeg = require('jpeg-js');
const { getProfile } = require('../stream-profiles');
const profile = getProfile(process.argv[2]);

process.env.CAMSTREAM_PORT = '18080';
const port = Number(process.env.CAMSTREAM_PORT);
const videosDir = path.join(os.tmpdir(), `camstream-test-videos-${process.pid}`);
const userDataDir = path.join(os.tmpdir(), `camstream-test-user-data-${process.pid}`);
const events = [];
const handlers = new Map();
const ipcEvents = new Map();
let openedFolder = null;
const virtualCameraRegistry = { installed: false, friendlyName: '', dllPath: '' };
const originalNetworkInterfaces = os.networkInterfaces;
os.networkInterfaces = () => ({
  'Radmin VPN': [{ address: '26.82.250.248', family: 'IPv4', internal: false }],
  Ethernet: [{ address: '192.168.1.89', family: 'IPv4', internal: false }],
  'Loopback Pseudo-Interface 1': [{ address: '127.0.0.1', family: 'IPv4', internal: true }]
});
const electron = {
  app: {
    whenReady: () => Promise.resolve(), quit() {}, on() {},
    getPath: name => name === 'videos' ? videosDir : (name === 'userData' ? userDataDir : process.cwd()),
    getVersion: () => '1.0.0'
  },
  BrowserWindow: class {
    constructor() {
      this.webContents = {
        send: (_name, event) => {
          events.push(event);
          if (event.type === 'frame') {
            setImmediate(() => ipcEvents.get('frame-rendered')?.({}));
          }
        },
        on() {}
      };
    }
    loadFile() {}
    on() {}
    isDestroyed() { return false; }
  },
  ipcMain: {
    handle: (name, fn) => handlers.set(name, fn),
    on: (name, fn) => ipcEvents.set(name, fn)
  },
  shell: { openPath: async folder => { openedFolder = folder; return ''; } }
};

const originalLoad = Module._load;
Module._load = function (request, parent, isMain) {
  if (request === 'electron') return electron;
  if (request === 'child_process') {
    return {
      ...childProcess,
      spawnSync: (command, args, options) => {
        if (command === 'reg.exe') {
          const key = String(args?.[1] || '').toLowerCase();
          if (key.includes('\\instance\\{b4e5b3a0-1b0c-4f8e-9d4d-8c5e7f3a2b1d}')) {
            return virtualCameraRegistry.installed
              ? { status: 0, stdout: `FriendlyName    REG_SZ    ${virtualCameraRegistry.friendlyName}\r\n` }
              : { status: 1, stdout: '' };
          }
          if (key.endsWith('\\inprocserver32')) {
            return virtualCameraRegistry.installed
              ? { status: 0, stdout: `(Predeterminado)    REG_SZ    ${virtualCameraRegistry.dllPath}\r\n` }
              : { status: 1, stdout: '' };
          }
          return { status: 1, stdout: '' };
        }
        return childProcess.spawnSync(command, args, options);
      }
    };
  }
  if (request === './ndi-sender.js' || request === './virtual-cam-writer.js') {
    throw new Error('Optional output unavailable');
  }
  return originalLoad.call(this, request, parent, isMain);
};
require('../main');

function request(url, method = 'GET', body) {
  return new Promise((resolve, reject) => {
    const req = http.request(`http://127.0.0.1:${port}${url}`, {
      method, headers: body ? { 'Content-Type': 'video/h264', 'X-JyroCam-Profile': profile.id } : {}
    }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks) }));
    });
    req.on('error', reject);
    req.end(body);
  });
}

async function run() {
  // Run without NDI: H.264 must still reach the desktop viewer.
  while (!handlers.has('get-info')) await new Promise(resolve => setTimeout(resolve, 20));
  let ready = false;
  for (let attempt = 0; attempt < 50 && !ready; attempt++) {
    try { ready = (await request('/status')).status === 200; }
    catch (_) { await new Promise(resolve => setTimeout(resolve, 50)); }
  }
  assert.ok(ready, 'HTTP server did not start');
  let info = handlers.get('get-info')();
  assert.equal(info.ndi, false);
  assert.equal(info.virtualCamInstalled, false);
  assert.equal(info.virtualCamUpdated, false);
  assert.equal(info.ips[0], '192.168.1.89',
    'Displayed address should prefer the physical LAN over the VPN');

  virtualCameraRegistry.installed = true;
  virtualCameraRegistry.friendlyName = 'CamStream Virtual Camera';
  virtualCameraRegistry.dllPath = path.join(userDataDir, 'virtual-camera', 'v1.0.0', 'CamStreamVirtualCam.dll');
  info = handlers.get('get-info')();
  assert.equal(info.virtualCamInstalled, true);
  assert.equal(info.virtualCamUpdated, false, 'Legacy registered driver should request an update');

  const driverSource = path.join(__dirname, '..', 'virtual-cam', 'bin', 'JyroCamVirtualCam.dll');
  const driverHash = fs.existsSync(driverSource)
    ? crypto.createHash('sha256').update(fs.readFileSync(driverSource)).digest('hex').slice(0, 12)
    : 'unavailable';
  const updatedDriverPath = path.join(userDataDir, 'virtual-camera', 'v1.0.0',
    `JyroCamVirtualCam-${driverHash}.dll`);
  fs.mkdirSync(path.dirname(updatedDriverPath), { recursive: true });
  fs.writeFileSync(updatedDriverPath, 'test driver');
  virtualCameraRegistry.friendlyName = 'JyroCam';
  virtualCameraRegistry.dllPath = updatedDriverPath;
  info = handlers.get('get-info')();
  assert.equal(info.virtualCamInstalled, true);
  assert.equal(info.virtualCamUpdated, true, 'Updated JyroCam driver should stay current after restart');
  assert.equal((await request('/status')).status, 200);

  const ffmpeg = require('ffmpeg-static');
  const source = spawn(ffmpeg, [
    '-loglevel', 'error', '-f', 'lavfi', '-i', `testsrc2=size=${profile.width}x${profile.height}:rate=${profile.fps}`,
    '-frames:v', '45', '-c:v', 'libx264', '-preset', 'ultrafast',
    '-tune', 'zerolatency', '-f', 'h264', 'pipe:1'
  ]);
  const stderr = [];
  const encodedChunks = [];
  source.stderr.on('data', chunk => stderr.push(chunk));
  for await (const chunk of source.stdout) {
    encodedChunks.push(chunk);
  }
  if (source.exitCode === null) await new Promise(resolve => source.once('close', resolve));
  assert.equal(source.exitCode, 0, Buffer.concat(stderr).toString());

   assert.equal((await request('/stream-h264', 'POST', Buffer.alloc(0))).status, 200);
   const negotiated = JSON.parse((await request('/status')).body).video;
   assert.equal(negotiated.id, profile.id);
   const recording = await handlers.get('start-recording')({}, { fps: 15 });
  assert.equal(recording.ok, true, recording.error);
  assert.equal(path.dirname(recording.path), videosDir);
  assert.equal(path.extname(recording.path), '.mp4');
  const recordingStarted = Date.now() + 5000;
  while (!events.some(event => event.type === 'recording-started') && Date.now() < recordingStarted) {
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  assert.ok(events.some(event => event.type === 'recording-started'), 'FFmpeg recorder did not start');

  // A persistent upload must parse NAL lengths even when split across HTTP chunks.
  const framesBeforeStream = events.filter(event => event.type === 'frame').length;
  const decodedBeforeStream = JSON.parse((await request('/status')).body).h264.decoder.framesDecoded;
  const streamed = await new Promise((resolve, reject) => {
    const req = http.request(`http://127.0.0.1:${port}/stream-h264`, {
      method: 'POST', headers: { 'Content-Type': 'application/x-h264-framed', 'X-JyroCam-Profile': profile.id }
    }, res => {
      res.resume();
      res.on('end', () => resolve(res.statusCode));
    });
    req.on('error', reject);
    for (const chunk of encodedChunks) {
      const prefix = Buffer.alloc(4);
      prefix.writeUInt32BE(chunk.length);
      req.write(prefix.subarray(0, 2));
      req.write(Buffer.concat([prefix.subarray(2), chunk]));
    }
    req.end();
  });
  assert.equal(streamed, 200);
  const streamStatus = JSON.parse((await request('/status')).body).h264;
  assert.ok(streamStatus.nals >= encodedChunks.length);
  assert.equal(streamStatus.active, false);
  const streamDeadline = Date.now() + 10000;
  while (events.filter(event => event.type === 'frame').length === framesBeforeStream && Date.now() < streamDeadline) {
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  const frame = events.find(event => event.type === 'frame');
  assert.ok(events.filter(event => event.type === 'frame').length > framesBeforeStream,
    'Persistent H264 upload produced no decoded frame');
  const frameDeadline = Date.now() + 10000;
  let decodedFrames = 0;
  while (decodedFrames < decodedBeforeStream + 12 && Date.now() < frameDeadline) {
    decodedFrames = JSON.parse((await request('/status')).body).h264.decoder.framesDecoded;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert.ok(decodedFrames >= decodedBeforeStream + 12,
    'Too few decoded frames reached the recorder');
   const decodedJpeg = jpeg.decode(Buffer.from(frame.b64, 'base64'));
    assert.equal(decodedJpeg.width, profile.width);
    assert.equal(decodedJpeg.height, profile.height);
  const status = JSON.parse((await request('/status')).body);
  assert.equal(status.connected, true);
  assert.ok(status.h264.requests > 0, 'H264 ingress metrics were not recorded');
  assert.equal(typeof status.h264.avgChunkGapMs, 'number');
  assert.equal(typeof status.h264.decoder.queueBytes, 'number');
  assert.equal(typeof status.h264.decoder.decodedFps, 'number');
  assert.equal(typeof status.h264.output.callbackMs, 'number');
  assert.equal(typeof status.h264.output.rendererAckMs, 'number');
  assert.equal((await request('/frame.jpg')).status, 200);

  assert.equal(handlers.get('stop-recording')().ok, true);
  const stopDeadline = Date.now() + 10000;
  while (!events.some(event => event.type === 'recording-stopped' || event.type === 'recording-error') && Date.now() < stopDeadline) {
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert.ok(events.some(event => event.type === 'recording-stopped'),
    events.find(event => event.type === 'recording-error')?.message || 'Recording did not finalize');
  assert.ok(fs.statSync(recording.path).size > 0, 'Recording file is empty');
  const probe = spawnSync(ffmpeg, ['-v', 'error', '-i', recording.path, '-f', 'null', '-']);
   assert.equal(probe.status, 0, Buffer.concat([probe.stdout || Buffer.alloc(0), probe.stderr || Buffer.alloc(0)]).toString());
   const metadata = spawnSync(ffmpeg, ['-v', 'info', '-i', recording.path, '-f', 'null', '-'], { encoding: 'utf8' });
   assert.ok(metadata.stderr.includes(`${profile.width}x${profile.height}`), metadata.stderr);
   assert.ok(metadata.stderr.includes(`${profile.fps} fps`), metadata.stderr);

  assert.deepEqual(await handlers.get('open-folder')(), { ok: true, path: videosDir });
  assert.equal(openedFolder, videosDir);
  fs.rmSync(videosDir, { recursive: true, force: true });
  fs.rmSync(userDataDir, { recursive: true, force: true });
  os.networkInterfaces = originalNetworkInterfaces;
  console.log(`${profile.id}: H264 -> viewer + MP4 recording + Videos folder: OK`);
}

run().then(() => process.exit(0), error => {
  console.error(error);
  process.exit(1);
});
