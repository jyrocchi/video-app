// ndi-sender.js
// Salida NDI opcional para usar CamStream como camara virtual en Windows.
// Requiere NDI Tools instalado (gratis en ndi.video) y los modulos koffi + jpeg-js.

let koffi = null;
let jpeg = null;
let loadError = null;
const fs = require('fs');
const path = require('path');
let runtime = null;
let availability = null;
let checkedAt = 0;

function loadRuntime() {
  if (runtime) return runtime;
  if (!koffi) throw new Error(loadError || 'Koffi no disponible');
  const candidates = [...NDI_DLL_CANDIDATES];
  for (const key of ['NDI_RUNTIME_DIR_V6', 'NDI_RUNTIME_DIR_V5', 'NDI_RUNTIME_DIR_V4']) {
    if (process.env[key]) candidates.unshift(path.join(process.env[key], 'Processing.NDI.Lib.x64.dll'));
  }
  // Tools installs also place the runtime alongside individual tools.
  const visit = (dir, depth) => {
    try {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const file = path.join(dir, entry.name);
        if (entry.isFile() && entry.name.toLowerCase() === 'processing.ndi.lib.x64.dll') candidates.push(file);
        else if (entry.isDirectory() && depth > 0) visit(file, depth - 1);
      }
    } catch (_) {}
  };
  visit(path.join(process.env.ProgramFiles || 'C:\\Program Files', 'NDI'), 3);
  for (const file of candidates) {
    try {
      const lib = koffi.load(file);
      if (!lib.func('NDIlib_initialize', 'bool', [])()) continue;
      runtime = lib;
      return runtime;
    } catch (_) {}
  }
  throw new Error('No se pudo cargar el runtime NDI x64. Instala NDI Runtime o NDI Tools y vuelve a intentar.');
}

function getNdiAvailability() {
  if (availability && (availability.available || Date.now() - checkedAt < 3000)) return availability;
  checkedAt = Date.now();
  try { loadRuntime(); availability = { available: true, error: null }; }
  catch (e) { availability = { available: false, error: e.message }; }
  return availability;
}

try { koffi = require('koffi'); } catch (e) { loadError = 'koffi: ' + e.message; }
try { jpeg = require('jpeg-js'); } catch (e) { loadError = (loadError ? loadError + '; ' : '') + 'jpeg-js: ' + e.message; }

const NDI_DLL_CANDIDATES = [
  'Processing.NDI.Lib.x64',
  'C:\\Program Files\\NDI\\NDI 6 Runtime\\v6\\Processing.NDI.Lib.x64.dll',
  'C:\\Program Files\\NDI\\NDI 6 Tools\\Runtime\\Processing.NDI.Lib.x64.dll',
  'C:\\Program Files\\NDI\\NDI 5 Tools\\Runtime\\Processing.NDI.Lib.x64.dll',
  'C:\\Program Files\\NDI\\NDI 4 Tools\\Runtime\\Processing.NDI.Lib.x64.dll'
];

const FOURCC_BGRA = 0x41524742; // 'BGRA' en little-endian

class NdiSender {
  constructor(name) {
    if (!koffi) throw new Error(loadError || 'koffi no instalado (npm install koffi)');
    if (!jpeg) throw new Error(loadError || 'jpeg-js no instalado (npm install jpeg-js)');

    const lib = loadRuntime();

    const sendCreateT = koffi.struct({
      p_ndi_name: 'string',
      p_groups: 'string',
      clock_video: 'bool',
      clock_audio: 'bool'
    });

    const NDIlib_send_create = lib.func('NDIlib_send_create', 'void*', [koffi.pointer(sendCreateT)]);

    const settings = {
      p_ndi_name: name,
      p_groups: '',
      // The camera is the 30fps clock. A second NDI clock blocks the Node loop.
      clock_video: false,
      clock_audio: false
    };

    this.pSend = NDIlib_send_create(settings);
    if (!this.pSend) {
      throw new Error('NDIlib_send_create fallo');
    }

    this.videoFrameT = koffi.struct({
      xres: 'int32_t',
      yres: 'int32_t',
      FourCC: 'uint32_t',
      frame_rate_N: 'int32_t',
      frame_rate_D: 'int32_t',
      picture_aspect_ratio: 'float',
      frame_format_type: 'uint32_t',
      timecode: 'int64_t',
      p_data: 'void*',
      line_stride_in_bytes: 'int32_t',
      p_metadata: 'string',
      timestamp: 'int64_t'
    });

    this.NDIlib_send_send_video_async_v2 =
      lib.func('NDIlib_send_send_video_async_v2', 'void', ['void*', koffi.pointer(this.videoFrameT)]);
    this.NDIlib_send_destroy = lib.func('NDIlib_send_destroy', 'void', ['void*']);

    this.width = 0;
    this.height = 0;
    this.bgraBuffer = null;
    this.bgraBuffers = null;
    this.bufferIndex = 0;
    this.lib = lib;
  }

  sendBgra(bgraBuffer, width, height, fps = 30) {
    if (!this.pSend) return;
    if (width !== this.width || height !== this.height || !this.bgraBuffer) {
      this.NDIlib_send_send_video_async_v2(this.pSend, null);
      this.width = width;
      this.height = height;
      this.bgraBuffers = [Buffer.allocUnsafe(width * height * 4), Buffer.allocUnsafe(width * height * 4)];
      this.bufferIndex = 0;
    }
    this.bgraBuffer = this.bgraBuffers[this.bufferIndex];
    this.bufferIndex ^= 1;
    bgraBuffer.copy(this.bgraBuffer, 0, 0, width * height * 4);

    const frame = {
      xres: width,
      yres: height,
      FourCC: FOURCC_BGRA,
      frame_rate_N: fps,
      frame_rate_D: 1,
      picture_aspect_ratio: width / height,
      frame_format_type: 1,
      timecode: Date.now() * 10000,
      p_data: this.bgraBuffer,
      line_stride_in_bytes: width * 4,
      p_metadata: '',
      timestamp: Date.now() * 10000
    };

    this.NDIlib_send_send_video_async_v2(this.pSend, frame);
  }

  send(jpegBuffer) {
    const decoded = jpeg.decode(jpegBuffer, { useTArray: true });
    const w = decoded.width;
    const h = decoded.height;
    const rgba = decoded.data;

    const bgra = Buffer.allocUnsafe(w * h * 4);
    for (let i = 0; i < rgba.length; i += 4) {
      bgra[i] = rgba[i + 2];
      bgra[i + 1] = rgba[i + 1];
      bgra[i + 2] = rgba[i];
      bgra[i + 3] = rgba[i + 3];
    }
    this.sendBgra(bgra, w, h);
  }

  close() {
    if (this.pSend) {
      try { this.NDIlib_send_send_video_async_v2(this.pSend, null); } catch (_) {}
      try { this.NDIlib_send_destroy(this.pSend); } catch (_) {}
      this.pSend = null;
    }
  }
}

module.exports = { NdiSender, getNdiAvailability };
