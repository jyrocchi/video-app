// One native decode, fixed 720p BGRA for NDI/DirectShow and native MJPEG for
// the viewer/recorder. No BMP encoding, per-pixel JS conversion or JS JPEG encode.
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

const WIDTH = 1280;
const HEIGHT = 720;
const FPS = 30;
const FRAME_BYTES = WIDTH * HEIGHT * 4;
let ffmpegPath;
try {
  ffmpegPath = require('ffmpeg-static');
  if (ffmpegPath?.includes('app.asar')) ffmpegPath = ffmpegPath.replace('app.asar', 'app.asar.unpacked');
  if (ffmpegPath && !path.isAbsolute(ffmpegPath)) ffmpegPath = path.resolve(ffmpegPath);
} catch (_) {}

class RawFrameParser {
  constructor(frameBytes, onFrame) {
    this.buffer = Buffer.allocUnsafe(frameBytes);
    this.used = 0;
    this.onFrame = onFrame;
  }

  push(chunk) {
    let offset = 0;
    while (offset < chunk.length) {
      const count = Math.min(this.buffer.length - this.used, chunk.length - offset);
      chunk.copy(this.buffer, this.used, offset, offset + count);
      this.used += count;
      offset += count;
      if (this.used === this.buffer.length) {
        // Valid only until the next frame: callbacks must copy if retaining it.
        this.onFrame(this.buffer);
        this.used = 0;
      }
    }
  }
}

class JpegFrameParser {
  constructor(onFrame) {
    this.onFrame = onFrame;
    this.parts = [];
    this.size = 0;
    this.previous = -1;
  }

  push(chunk) {
    let start = 0;
    for (let i = 0; i < chunk.length; i++) {
      const value = chunk[i];
      if (this.previous === 0xff && value === 0xd9) {
        this.parts.push(chunk.subarray(start, i + 1));
        this.size += i + 1 - start;
        this.onFrame(Buffer.concat(this.parts, this.size));
        this.parts = [];
        this.size = 0;
        start = i + 1;
      }
      this.previous = value;
    }
    if (start < chunk.length) {
      this.parts.push(chunk.subarray(start));
      this.size += chunk.length - start;
    }
    if (this.size > 8 * 1024 * 1024) {
      throw new Error('MJPEG frame exceeded bounded parser capacity');
    }
  }
}

class H264Decoder {
  constructor() {
    this.proc = null;
    this.ffmpegReady = false;
    this.lastError = null;
    this.stderrTail = '';
    this.waitingForConfig = true;
    this.onFrame = null;
    this.onJpeg = null;
    this.queueBytes = 0;
    this.peakQueueBytes = 0;
    this.totalFramesDecoded = 0;
    this.totalJpegFrames = 0;
    this.decodedFps = 0;
    this.lastFrameAt = 0;
    this.fpsWindowFrames = 0;
    this.fpsWindowStartedAt = Date.now();
  }

  start() {
    if (!ffmpegPath || !fs.existsSync(ffmpegPath)) {
      this.lastError = 'ffmpeg-static no encontrado';
      return false;
    }
    if (this.proc) return true;
    this.lastError = null;
    this.stderrTail = '';
    this.waitingForConfig = true;
    this.queueBytes = 0;
    const scale = `scale=${WIDTH}:${HEIGHT}:force_original_aspect_ratio=decrease:flags=fast_bilinear,pad=${WIDTH}:${HEIGHT}:(ow-iw)/2:(oh-ih)/2,setsar=1`;
    const args = [
      '-hide_banner', '-loglevel', 'warning',
      '-probesize', '32', '-analyzeduration', '0',
      '-fflags', '+genpts+flush_packets', '-flags', 'low_delay',
      '-threads', '2', '-thread_type', 'slice',
      '-framerate', String(FPS), '-reinit_filter', '0', '-f', 'h264', '-i', 'pipe:0',
      '-filter_complex_threads', '2',
      '-filter_complex', `[0:v]setpts=N/(${FPS}*TB),${scale},split=2[raw][preview]`,
      '-map', '[raw]', '-an', '-c:v', 'rawvideo', '-threads', '1',
      '-pix_fmt', 'bgra', '-fps_mode', 'passthrough', '-flush_packets', '1',
      '-f', 'rawvideo', 'pipe:1',
      '-map', '[preview]', '-an', '-c:v', 'mjpeg', '-threads', '2',
      '-q:v', '5', '-pix_fmt', 'yuvj420p', '-fps_mode', 'passthrough',
      '-flush_packets', '1', '-f', 'image2pipe', 'pipe:3'
    ];
    try {
      const proc = spawn(ffmpegPath, args, {
        stdio: ['pipe', 'pipe', 'pipe', 'pipe'], windowsHide: true
      });
      this.proc = proc;
      this.ffmpegReady = true;
      console.log('H264 decoder: native 1280x720@30 BGRA + MJPEG');
      proc.stderr.on('data', data => {
        const message = data.toString().trim();
        this.stderrTail = (this.stderrTail + message + '\n').slice(-2000);
        if (message) console.warn('[ffmpeg]', message);
      });
      proc.stdin.on('error', error => {
        this.lastError = error.message;
        this.ffmpegReady = false;
      });
      proc.on('error', error => {
        this.lastError = error.message;
        this.ffmpegReady = false;
      });
      proc.on('exit', (code, signal) => {
        if (this.proc !== proc) return;
        this.ffmpegReady = false;
        this.proc = null;
        if (code !== 0) this.lastError = `FFmpeg exited ${code ?? signal}: ${this.stderrTail}`;
      });
      const raw = new RawFrameParser(FRAME_BYTES, frame => {
        this.totalFramesDecoded++;
        this.lastFrameAt = Date.now();
        this.fpsWindowFrames++;
        const elapsed = this.lastFrameAt - this.fpsWindowStartedAt;
        if (elapsed >= 1000) {
          this.decodedFps = Number((this.fpsWindowFrames * 1000 / elapsed).toFixed(1));
          this.fpsWindowFrames = 0;
          this.fpsWindowStartedAt = this.lastFrameAt;
        }
        this.onFrame?.(frame, WIDTH, HEIGHT, this.lastFrameAt);
      });
      const jpeg = new JpegFrameParser(frame => {
        this.totalJpegFrames++;
        this.onJpeg?.(frame, WIDTH, HEIGHT, Date.now());
      });
      proc.stdout.on('data', chunk => raw.push(chunk));
      proc.stdio[3].on('data', chunk => jpeg.push(chunk));
      return true;
    } catch (error) {
      this.lastError = error.message;
      return false;
    }
  }

  pushH264(data) {
    if (!this.proc || !this.ffmpegReady) return false;
    if (this.waitingForConfig) {
      // A reconnect may start mid-GOP. Do not initialize FFmpeg with a slice
      // referencing unavailable PPS; Android repeats SPS/PPS at every IDR.
      let configOffset = -1;
      for (let i = 0; i + 4 < data.length; i++) {
        if (data[i] !== 0 || data[i + 1] !== 0) continue;
        const startBytes = data[i + 2] === 1 ? 3 :
          (data[i + 2] === 0 && data[i + 3] === 1 ? 4 : 0);
        if (startBytes && (data[i + startBytes] & 0x1f) === 7) { configOffset = i; break; }
      }
      if (configOffset < 0) return true;
      data = data.subarray(configOffset);
      this.waitingForConfig = false;
    }
    this.queueBytes += data.length;
    this.peakQueueBytes = Math.max(this.peakQueueBytes, this.queueBytes);
    try {
      return this.proc.stdin.write(data, () => {
        this.queueBytes = Math.max(0, this.queueBytes - data.length);
      });
    } catch (error) {
      this.queueBytes = Math.max(0, this.queueBytes - data.length);
      this.lastError = error.message;
      return false;
    }
  }

  getStats() {
    return {
      width: WIDTH, height: HEIGHT, targetFps: FPS,
      queueBytes: this.queueBytes, peakQueueBytes: this.peakQueueBytes,
      decodedFps: Date.now() - this.lastFrameAt < 2000 ? this.decodedFps : 0,
      framesDecoded: this.totalFramesDecoded, jpegFrames: this.totalJpegFrames,
      error: this.lastError
    };
  }

  stop() {
    const proc = this.proc;
    this.proc = null;
    this.ffmpegReady = false;
    if (!proc) return;
    try { proc.stdin.end(); } catch (_) {}
    try { proc.kill(); } catch (_) {}
  }
}

module.exports = { H264Decoder, RawFrameParser, JpegFrameParser, WIDTH, HEIGHT, FPS, FRAME_BYTES };
