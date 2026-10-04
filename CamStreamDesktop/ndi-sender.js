// ndi-sender.js
// Salida NDI opcional para usar CamStream como camara virtual en Windows.
// Requiere NDI Tools instalado (gratis en ndi.video) y los modulos koffi + jpeg-js.

let koffi = null;
let jpeg = null;
let loadError = null;

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

    let lib = null;
    let lastErr = null;
    for (const candidate of NDI_DLL_CANDIDATES) {
      try {
        lib = koffi.load(candidate);
        break;
      } catch (e) {
        lastErr = e.message;
      }
    }
    if (!lib) {
      throw new Error('No se encontro Processing.NDI.Lib.x64.dll. Instala NDI Tools desde ndi.video');
    }

    const NDIlib_initialize = lib.func('NDIlib_initialize', 'bool', []);
    if (!NDIlib_initialize()) {
      throw new Error('NDIlib_initialize fallo');
    }

    const sendCreateT = koffi.struct('NDIlib_send_create_t', {
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

    this.videoFrameT = koffi.struct('NDIlib_video_frame_v2_t', {
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

module.exports = { NdiSender };
