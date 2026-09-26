const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const http = require('http');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { spawn } = require('child_process');
const { spawnSync } = require('child_process');
const ffmpegPath = require('ffmpeg-static');

let sharedFrameBuf = null;
let virtualCamWriter = null;
try {
  const VirtualCamWriter = require('./virtual-cam-writer.js');
  virtualCamWriter = new VirtualCamWriter();
  if (virtualCamWriter.open()) {
    console.log('VirtualCam writer opened (shared memory OK)');
  } else {
    virtualCamWriter = null;
    console.log('VirtualCam writer failed to open');
  }
} catch (e) {
  console.log('VirtualCam writer not available:', e.message);
}
const jpeg = require('jpeg-js');
const { H264Decoder } = require('./h264-decoder.js');

const PORT = Number(process.env.CAMSTREAM_PORT) || 8080;
const VIRTUAL_CAM_CLSID = '{B4E5B3A0-1B0C-4F8E-9D4D-8C5E7F3A2B1D}';

let mainWindow = null;
let httpServer = null;
let latestFrameB64 = null;
let latestFrameTime = 0;
let latestFrameBuffer = null;
let latestBgraBuffer = null;
let latestBgraWidth = 0;
let latestBgraHeight = 0;
const sseClients = new Set();
let recordingProc = null;
let recordingPath = null;
let recordingFps = 30;
let frameCount = 0;
let fpsCalcStart = Date.now();
let currentFps = 0;
let ndiEnabled = false;
let ndiSender = null;
let ndiInitError = null;
let h264Decoder = null;

function getLocalIPs() {
  const ifaces = os.networkInterfaces();
  const ips = [];
  for (const name of Object.keys(ifaces)) {
    for (const iface of ifaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        ips.push(iface.address);
      }
    }
  }
  return ips;
}

function getVirtualCamDllSource() {
  return app.isPackaged
    ? path.join(process.resourcesPath, 'app.asar.unpacked', 'virtual-cam', 'bin', 'CamStreamVirtualCam.dll')
    : path.join(__dirname, 'virtual-cam', 'bin', 'CamStreamVirtualCam.dll');
}

function isVirtualCamRegistered() {
  const key = `HKCU\\Software\\Classes\\CLSID\\{860BB310-5D01-11d0-BD3B-00A0C911CE86}\\Instance\\${VIRTUAL_CAM_CLSID}`;
  const result = spawnSync('reg.exe', ['query', key], { windowsHide: true, encoding: 'utf8' });
  return result.status === 0;
}

function setVirtualCamRegistration(install) {
  const source = getVirtualCamDllSource();
  if (!fs.existsSync(source)) return { ok: false, error: `No se encuentra el filtro DirectShow: ${source}` };

  const installDir = path.join(app.getPath('userData'), 'virtual-camera', `v${app.getVersion()}`);
  const installedDll = path.join(installDir, 'CamStreamVirtualCam.dll');
  try {
    if (install) {
      fs.mkdirSync(installDir, { recursive: true });
      if (!fs.existsSync(installedDll)) fs.copyFileSync(source, installedDll);
    }
    const dllPath = install ? installedDll : (fs.existsSync(installedDll) ? installedDll : source);
    const koffi = require('koffi');
    const library = koffi.load(dllPath);
    const entry = library.func(install ? 'DllRegisterServer' : 'DllUnregisterServer', 'int', []);
    const hr = entry();
    if (hr < 0) return { ok: false, error: `${install ? 'Registro' : 'Desregistro'} HRESULT 0x${(hr >>> 0).toString(16)}` };
    return { ok: true, installed: install };
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

function broadcastFrame(frameB64) {
  for (const client of sseClients) {
    try { client.write(`data: ${frameB64}\n\n`); } catch (_) {
      sseClients.delete(client);
    }
  }
}

function publishJpeg(body) {
  latestFrameBuffer = body;
  latestFrameB64 = body.toString('base64');
  latestFrameTime = Date.now();
  broadcastFrame(latestFrameB64);
  updateFps();
  notifyRenderer({ type: 'frame', b64: latestFrameB64, size: body.length });
}

function notifyRenderer(payload) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('event', payload);
  }
}

function updateFps() {
  frameCount++;
  const elapsed = Date.now() - fpsCalcStart;
  if (elapsed >= 1000) {
    currentFps = Math.round((frameCount * 1000) / elapsed);
    frameCount = 0;
    fpsCalcStart = Date.now();
    notifyRenderer({ type: 'fps', value: currentFps });
  }
}

function sendToNdi(jpegBuffer) {
  if (!ndiEnabled || !ndiSender) return;
  try {
    ndiSender.send(jpegBuffer);
  } catch (e) {
    if (!ndiInitError) {
      ndiInitError = e.message;
      console.warn('NDI send error:', e.message);
    }
  }
}

function processFrameAsync(body) {
  sendToNdi(body);

  if (virtualCamWriter) {
    try {
      const decoded = jpeg.decode(body, { useTArray: true });
      virtualCamWriter.write(decoded.data, decoded.width, decoded.height);
    } catch (e) {
      // ignore decode errors
    }
  }

  if (recordingProc) {
    try { recordingProc.stdin.write(body); } catch (_) {}
  }
}

function createHttpServer() {
  httpServer = http.createServer((req, res) => {
    const url = (req.url || '').split('?')[0];

    if (url === '/' || url === '/viewer' || url === '/index.html') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      fs.createReadStream(path.join(__dirname, 'viewer.html')).pipe(res);
      return;
    }

    if (url === '/styles.css') {
      res.writeHead(200, { 'Content-Type': 'text/css' });
      fs.createReadStream(path.join(__dirname, 'styles.css')).pipe(res);
      return;
    }

    if (url === '/upload' && req.method === 'POST') {
      const chunks = [];
      let total = 0;
      const limit = 16 * 1024 * 1024;
      req.on('data', (c) => {
        total += c.length;
        if (total > limit) { req.destroy(); return; }
        chunks.push(c);
      });
      req.on('end', () => {
        try {
          const body = Buffer.concat(chunks);
          const ct = (req.headers['content-type'] || '').toLowerCase();
          let b64;
          if (ct.includes('image/jpeg') || ct.includes('application/octet-stream')) {
            b64 = body.toString('base64');
          } else {
            const text = body.toString('utf8');
            const match = text.match(/^data:image\/\w+;base64,(.+)$/);
            if (!match) { res.writeHead(400); res.end('bad data'); return; }
            b64 = match[1];
          }
          const jpegBody = ct.includes('image/jpeg') || ct.includes('application/octet-stream')
            ? body : Buffer.from(b64, 'base64');
          publishJpeg(jpegBody);
          res.writeHead(200, { 'Content-Type': 'text/plain' });
          res.end('ok');

          setImmediate(() => processFrameAsync(jpegBody));
        } catch (e) {
          res.writeHead(500); res.end('err');
        }
      });
      return;
    }

    if (url === '/upload-h264' && req.method === 'POST') {
      const chunks = [];
      let total = 0;
      const limit = 4 * 1024 * 1024;
      req.on('data', (c) => {
        total += c.length;
        if (total > limit) { req.destroy(); return; }
        chunks.push(c);
      });
      req.on('end', () => {
        const body = Buffer.concat(chunks);
        if (h264Decoder && h264Decoder.ffmpegReady) {
          h264Decoder.pushH264(body);
          res.writeHead(200, { 'Content-Type': 'text/plain' });
          res.end('ok');
        } else {
          res.writeHead(503, { 'Content-Type': 'text/plain' });
          res.end(h264Decoder?.lastError || 'Decodificador H264 no disponible');
        }
      });
      return;
    }

    if (url === '/stream') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive'
      });
      res.write(': connected\n\n');
      if (latestFrameB64) res.write(`data: ${latestFrameB64}\n\n`);
      sseClients.add(res);
      const ka = setInterval(() => {
        try { res.write(': ka\n\n'); } catch (_) {}
      }, 15000);
      req.on('close', () => { clearInterval(ka); sseClients.delete(res); });
      return;
    }

    if (url === '/frame.jpg') {
      if (!latestFrameBuffer) { res.writeHead(404); res.end('no frame'); return; }
      res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store' });
      res.end(latestFrameBuffer);
      return;
    }

    if (url === '/status') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        connected: !!latestFrameB64 && Date.now() - latestFrameTime < 5000,
        lastFrameAge: latestFrameB64 ? Math.floor((Date.now() - latestFrameTime) / 1000) : null,
        clients: sseClients.size,
        recording: !!recordingProc,
        fps: currentFps,
        ndi: ndiEnabled,
        ndiError: ndiInitError
      }));
      return;
    }

    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
  });

  httpServer.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      console.error(`Port ${PORT} in use. Try closing any other CamStream/server.js process.`);
    } else {
      console.error('HTTP server error:', err);
    }
    if (mainWindow) {
      mainWindow.webContents.send('event', { type: 'server-error', code: err.code, port: PORT });
    }
  });

  httpServer.keepAliveTimeout = 30000;
  httpServer.headersTimeout = 31000;
  httpServer.requestTimeout = 30000;
  httpServer.listen(PORT, '0.0.0.0', () => {
    console.log(`HTTP server on http://localhost:${PORT}`);
  });
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 960,
    height: 720,
    minWidth: 640,
    minHeight: 480,
    backgroundColor: '#0a0a0a',
    title: 'CamStream Desktop',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  mainWindow.loadFile('viewer.html');
  mainWindow.on('closed', () => { mainWindow = null; });
}

function startRecording(outputPath, fps) {
  if (recordingProc) return { ok: false, error: 'already recording' };
  if (!ffmpegPath) return { ok: false, error: 'ffmpeg-static binary not found' };

  recordingFps = fps || 30;
  recordingPath = outputPath;

  recordingProc = spawn(ffmpegPath, [
    '-y',
    '-f', 'image2pipe',
    '-framerate', String(recordingFps),
    '-i', '-',
    '-c:v', 'libx264',
    '-preset', 'ultrafast',
    '-pix_fmt', 'yuv420p',
    '-movflags', '+faststart',
    outputPath
  ], { stdio: ['pipe', 'ignore', 'pipe'] });

  let stderrBuf = '';
  recordingProc.stderr.on('data', (d) => { stderrBuf += d.toString(); });

  recordingProc.on('error', (err) => {
    console.error('ffmpeg error:', err);
    notifyRenderer({ type: 'recording-error', message: err.message });
    recordingProc = null;
  });

  recordingProc.on('exit', (code) => {
    console.log('ffmpeg exit code:', code, 'stderr:', stderrBuf.slice(-500));
    notifyRenderer({ type: 'recording-stopped', code, path: recordingPath });
    recordingProc = null;
  });

  notifyRenderer({ type: 'recording-started', path: outputPath });
  return { ok: true, path: outputPath };
}

function stopRecording() {
  if (!recordingProc) return { ok: false, error: 'not recording' };
  try { recordingProc.stdin.end(); } catch (_) {}
  setTimeout(() => {
    if (recordingProc) {
      try { recordingProc.kill(); } catch (_) {}
    }
  }, 1500);
  return { ok: true };
}

function startDecoder() {
  h264Decoder = new H264Decoder();
  h264Decoder.onFrame = (bgraBuf, width, height, ts) => {
    if (ndiEnabled && ndiSender) {
      try { ndiSender.sendBgra(bgraBuf, width, height); } catch (e) {
        console.warn('NDI frame error:', e.message);
      }
    }
    if (virtualCamWriter) {
      try { virtualCamWriter.writeBgra(bgraBuf, width, height); } catch (e) {
        console.warn('VirtualCam frame error:', e.message);
      }
    }
    latestBgraBuffer = bgraBuf;
    latestBgraWidth = width;
    latestBgraHeight = height;
    latestFrameTime = ts;

    // The desktop viewer, SSE clients and recorder all consume JPEG frames.
    const rgba = Buffer.from(bgraBuf);
    for (let i = 0; i < rgba.length; i += 4) {
      const blue = rgba[i];
      rgba[i] = rgba[i + 2];
      rgba[i + 2] = blue;
    }
    const frame = jpeg.encode({ data: rgba, width, height }, 65).data;
    publishJpeg(frame);
    if (recordingProc) {
      try { recordingProc.stdin.write(frame); } catch (_) {}
    }
  };
  if (!h264Decoder.start()) {
    console.error('H264 decoder unavailable:', h264Decoder.lastError);
    notifyRenderer({ type: 'decoder-error', message: h264Decoder.lastError });
  }
}

function tryEnableNdi() {
  try {
    const { NdiSender } = require('./ndi-sender.js');
    ndiSender = new NdiSender('CamStream Desktop');
    ndiEnabled = true;
    console.log('NDI sender initialized');

  } catch (e) {
    ndiEnabled = false;
    ndiInitError = e.message;
    console.warn('NDI not available:', e.message);
  }
}

ipcMain.handle('start-recording', async (_event, { fps }) => {
  if (!mainWindow) return { ok: false };
  const defaultName = `camstream-${new Date().toISOString().replace(/[:.]/g, '-')}.mp4`;
  const result = await dialog.showSaveDialog(mainWindow, {
    title: 'Guardar grabacion',
    defaultPath: path.join(app.getPath('videos') || os.homedir(), defaultName),
    filters: [{ name: 'Video MP4', extensions: ['mp4'] }]
  });
  if (result.canceled || !result.filePath) return { ok: false, canceled: true };
  return startRecording(result.filePath, fps);
});

ipcMain.handle('stop-recording', () => stopRecording());

ipcMain.handle('get-info', () => ({
  port: PORT,
  ips: getLocalIPs(),
  ndi: ndiEnabled,
  ndiError: ndiInitError,
  ffmpeg: !!ffmpegPath,
  virtualCamInstalled: isVirtualCamRegistered()
}));

ipcMain.handle('set-virtual-camera', (_event, install) => {
  const result = setVirtualCamRegistration(!!install);
  if (result.ok) result.installed = isVirtualCamRegistered();
  return result;
});

ipcMain.handle('open-folder', async () => {
  const folder = recordingPath ? path.dirname(recordingPath) : app.getPath('videos');
  await shell.openPath(folder);
  return { ok: true };
});

ipcMain.handle('toggle-ndi', async () => {
  if (ndiEnabled) {
    if (ndiSender) { try { ndiSender.close(); } catch (_) {} }
    ndiSender = null;
    ndiEnabled = false;
  } else {
    tryEnableNdi();
    if (ndiEnabled) launchNdiWebcamIfPossible();
  }
  return { ndi: ndiEnabled, ndiError: ndiInitError };
});

function launchNdiWebcamIfPossible() {
  const candidates = [
    'C:\\Program Files\\NDI\\NDI 6 Tools\\Webcam\\Webcam.exe',
    'C:\\Program Files\\NDI\\NDI 5 Tools\\Webcam\\Webcam.exe',
    'C:\\Program Files\\NDI\\NDI 4 Tools\\Webcam\\Webcam.exe'
  ];
  for (const p of candidates) {
    if (fs.existsSync(p)) {
      try {
        spawn(p, [], { detached: true, stdio: 'ignore' }).unref();
        console.log('NDI Webcam Input launched:', p);
      } catch (e) {
        console.warn('No se pudo lanzar NDI Webcam Input:', e.message);
      }
      return;
    }
  }
  console.log('NDI Webcam Input no encontrado (instalado pero no en rutas conocidas)');
}

app.whenReady().then(() => {
  createHttpServer();
  createWindow();
  startDecoder();
  tryEnableNdi();
});

app.on('window-all-closed', () => {
    stopRecording();
    if (httpServer) httpServer.close();
    if (h264Decoder) try { h264Decoder.stop(); } catch (_) {}
    if (ndiSender) try { ndiSender.close(); } catch (_) {}
    if (virtualCamWriter) try { virtualCamWriter.close(); } catch (_) {}
    if (process.platform !== 'darwin') app.quit();
  });

app.on('before-quit', () => {
  stopRecording();
});
