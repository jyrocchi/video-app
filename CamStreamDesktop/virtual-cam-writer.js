const koffi = require('koffi');

const SHARED_MEM_NAME = 'Local\\CamStreamVirtualCam_Frame';
const FRAME_WIDTH = 640;
const FRAME_HEIGHT = 480;
const RGB_SIZE = FRAME_WIDTH * FRAME_HEIGHT * 3;
const SHARED_FRAME_SIZE = 32 + RGB_SIZE;

class VirtualCamWriter {
  constructor() {
    this.k32 = null;
    this.RtlMoveMemory = null;
    this.hMap = null;
    this.view = null;
    this.staging = null;
    this.headerSnapshot = Buffer.alloc(32);
    this.opened = false;
    this.UnmapViewOfFile = null;
    this.CloseHandle = null;
  }

  open() {
    try {
      this.k32 = koffi.load('kernel32.dll');
      const CreateFileMappingW = this.k32.func(
        'CreateFileMappingW', 'void*', ['void*', 'void*', 'uint32_t', 'uint32_t', 'uint32_t', 'str16']);
      const MapViewOfFile = this.k32.func(
        'MapViewOfFile', 'void*', ['void*', 'uint32_t', 'uint32_t', 'uint32_t', 'size_t']);
      const UnmapViewOfFile = this.k32.func(
        'UnmapViewOfFile', 'int', ['void*']);
      const CloseHandle = this.k32.func(
        'CloseHandle', 'int', ['void*']);
      this.RtlMoveMemory = this.k32.func(
        'RtlMoveMemory', 'void*', ['void*', 'void*', 'size_t']);

      this.hMap = CreateFileMappingW(null, null, 0x04, 0, SHARED_FRAME_SIZE, SHARED_MEM_NAME);
      if (!this.hMap) {
        console.error('VirtualCam: CreateFileMappingW returned NULL');
        return false;
      }
      this.view = MapViewOfFile(this.hMap, 0x000F001F, 0, 0, SHARED_FRAME_SIZE);
      if (!this.view) {
        console.error('VirtualCam: MapViewOfFile returned NULL');
        return false;
      }

      this.staging = Buffer.alloc(SHARED_FRAME_SIZE);
      this.writeHeader();
      this.RtlMoveMemory(this.view, this.staging, SHARED_FRAME_SIZE);

      this.UnmapViewOfFile = UnmapViewOfFile;
      this.CloseHandle = CloseHandle;
      this.opened = true;
      console.log('VirtualCam: shared memory opened OK, size=', SHARED_FRAME_SIZE);
      return true;
    } catch (e) {
      console.error('VirtualCamWriter.open failed:', e.message, e.stack);
      return false;
    }
  }

  writeHeader() {
    this.staging.writeUInt32LE(0x434D5354, 0);     // magic
    this.staging.writeUInt32LE(FRAME_WIDTH, 4);    // width
    this.staging.writeUInt32LE(FRAME_HEIGHT, 8);   // height
    this.staging.writeUInt32LE(FRAME_WIDTH * 3, 12); // stride
    this.staging.writeUInt32LE(0, 16);              // no active DirectShow client
    this.staging.writeBigUInt64LE(BigInt(Date.now()), 20); // timestamp
    this.staging.writeUInt32LE(RGB_SIZE, 28);       // dataSize
  }

  write(rgba, w, h) {
    if (!this.opened) return false;
    try {
      const dw = FRAME_WIDTH, dh = FRAME_HEIGHT;
      const sw = w, sh = h;
      const dst = this.staging;
      const offset = 32; // skip header

      for (let y = 0; y < dh; y++) {
        // BI_RGB / RGB24 is stored as BGR and positive biHeight is bottom-up.
        const srcY = sh - 1 - Math.floor(y * sh / dh);
        for (let x = 0; x < dw; x++) {
          const srcX = Math.floor(x * sw / dw);
          const srcIdx = (srcY * sw + srcX) * 4;
          const dstIdx = offset + (y * dw + x) * 3;
          dst[dstIdx] = rgba[srcIdx + 2];
          dst[dstIdx + 1] = rgba[srcIdx + 1];
          dst[dstIdx + 2] = rgba[srcIdx];
        }
        }
        // Update timestamp
        this.staging.writeBigUInt64LE(BigInt(Date.now()), 20);
        // Flush to shared memory
        this.flushToSharedMemory();
      return true;
    } catch (e) {
      console.error('VirtualCam.write failed:', e.message);
      return false;
    }
  }

  writeBgra(bgra, w, h) {
    if (!this.opened) return false;
    try {
      const dw = FRAME_WIDTH, dh = FRAME_HEIGHT;
      const sw = w, sh = h;
      const dst = this.staging;
      const offset = 32;

      for (let y = 0; y < dh; y++) {
        // FFmpeg supplies top-down BGRA; DirectShow RGB24 expects bottom-up BGR.
        const srcY = sh - 1 - Math.floor(y * sh / dh);
        for (let x = 0; x < dw; x++) {
          const srcX = Math.floor(x * sw / dw);
          const srcIdx = (srcY * sw + srcX) * 4;
          const dstIdx = offset + (y * dw + x) * 3;
          dst[dstIdx]     = bgra[srcIdx];     // B
          dst[dstIdx + 1] = bgra[srcIdx + 1]; // G
          dst[dstIdx + 2] = bgra[srcIdx + 2]; // R
        }
      }
      this.staging.writeBigUInt64LE(BigInt(Date.now()), 20);
      this.flushToSharedMemory();
      return true;
    } catch (e) {
      console.error('VirtualCam.writeBgra failed:', e.message);
      return false;
    }
  }

  isConnected() {
    if (!this.opened || !this.view) return false;
    try {
      this.RtlMoveMemory(this.headerSnapshot, this.view, this.headerSnapshot.length);
      return this.headerSnapshot.readUInt32LE(16) === 1;
    } catch (_) {
      return false;
    }
  }

  flushToSharedMemory() {
    // The DirectShow filter owns this word; preserve its live connection flag
    // when publishing the rest of the frame header and pixels.
    this.RtlMoveMemory(this.headerSnapshot, this.view, this.headerSnapshot.length);
    this.staging.writeUInt32LE(this.headerSnapshot.readUInt32LE(16), 16);
    this.RtlMoveMemory(this.view, this.staging, SHARED_FRAME_SIZE);
  }

  close() {
    if (this.view) try { this.UnmapViewOfFile(this.view); } catch (_) {}
    if (this.hMap) try { this.CloseHandle(this.hMap); } catch (_) {}
    this.opened = false;
  }
}

module.exports = VirtualCamWriter;
