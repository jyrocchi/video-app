const assert = require('node:assert/strict');
const http = require('node:http');
const { spawn } = require('node:child_process');
const Module = require('node:module');
const jpeg = require('jpeg-js');

process.env.CAMSTREAM_PORT = '18080';
const port = Number(process.env.CAMSTREAM_PORT);
const events = [];
const handlers = new Map();
const ipcEvents = new Map();
const electron = {
  app: { whenReady: () => Promise.resolve(), quit() {}, on() {}, getPath: () => process.cwd() },
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
  dialog: {},
  shell: {}
};

const originalLoad = Module._load;
Module._load = function (request, parent, isMain) {
  if (request === 'electron') return electron;
  if (request === './ndi-sender.js' || request === './virtual-cam-writer.js') {
    throw new Error('Optional output unavailable');
  }
  return originalLoad.call(this, request, parent, isMain);
};
require('../main');

function request(url, method = 'GET', body) {
  return new Promise((resolve, reject) => {
    const req = http.request(`http://127.0.0.1:${port}${url}`, {
      method, headers: body ? { 'Content-Type': 'video/h264' } : {}
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
  assert.equal(handlers.get('get-info')().ndi, false);
  assert.equal((await request('/status')).status, 200);

  const ffmpeg = require('ffmpeg-static');
  const source = spawn(ffmpeg, [
    '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=size=640x480:rate=15',
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

  // A persistent upload must parse NAL lengths even when split across HTTP chunks.
  const framesBeforeStream = events.filter(event => event.type === 'frame').length;
  const streamed = await new Promise((resolve, reject) => {
    const req = http.request(`http://127.0.0.1:${port}/stream-h264`, {
      method: 'POST', headers: { 'Content-Type': 'application/x-h264-framed' }
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
  assert.equal(jpeg.decode(Buffer.from(frame.b64, 'base64')).width, 640);
  const status = JSON.parse((await request('/status')).body);
  assert.equal(status.connected, true);
  assert.ok(status.h264.requests > 0, 'H264 ingress metrics were not recorded');
  assert.equal(typeof status.h264.avgChunkGapMs, 'number');
  assert.equal(typeof status.h264.decoder.queueBytes, 'number');
  assert.equal(typeof status.h264.decoder.decodedFps, 'number');
  assert.equal(typeof status.h264.output.callbackMs, 'number');
  assert.equal(typeof status.h264.output.rendererAckMs, 'number');
  assert.equal((await request('/frame.jpg')).status, 200);
  console.log('H264 -> HTTP -> decoder -> JPEG -> viewer: OK');
}

run().then(() => process.exit(0), error => {
  console.error(error);
  process.exit(1);
});
