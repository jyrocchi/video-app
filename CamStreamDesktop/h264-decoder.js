// h264-decoder.js
// Decodifica H.264 desde el celular usando ffmpeg-static y entrega frames BGRA a NDI + virtual-cam.

const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const os = require('os');

const WIDTH = 640;
const HEIGHT = 480;
const FRAME_BYTES = WIDTH * HEIGHT * 4; // BGRA

let ffmpegPath;
try {
  ffmpegPath = require('ffmpeg-static');
  if (ffmpegPath && ffmpegPath.includes('app.asar')) {
    ffmpegPath = ffmpegPath.replace('app.asar', 'app.asar.unpacked');
  }
  if (ffmpegPath && !path.isAbsolute(ffmpegPath)) {
    ffmpegPath = path.resolve(ffmpegPath);
  }
} catch (e) {
  ffmpegPath = null;
}

class H264Decoder {
  constructor() {
    this.proc = null;
    this.ffmpegReady = false;
    this.lastError = null;
    this.onFrame = null; // (bgraBuffer, width, height, timestampMs)
    this.queueBytes = 0;
    this.framesDecoded = 0;
    this.lastReport = Date.now();
    this.startedAt = 0;
    this._inputDrainTimer = null;
  }

  start() {
    if (!ffmpegPath || !fs.existsSync(ffmpegPath)) {
      this.lastError = 'ffmpeg-static no encontrado';
      console.error(this.lastError);
      return false;
    }
    if (this.proc) return true;

    const args = [
      '-loglevel', 'warning',
      '-probesize', '32',
      '-analyzeduration', '0',
      '-fflags', '+genpts+flush_packets',
      '-err_detect', 'ignore_err',
      '-f', 'h264',
      '-i', 'pipe:0',
      '-pix_fmt', 'bgra',
      '-s', `${WIDTH}x${HEIGHT}`,
      '-f', 'rawvideo',
      'pipe:1'
    ];

    try {
      this.proc = spawn(ffmpegPath, args, {
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true
      });
      this.startedAt = Date.now();
      this.ffmpegReady = true;
      console.log('H264 decoder started:', ffmpegPath);

      this.proc.stderr.on('data', (d) => {
        const msg = d.toString().trim();
        if (msg) {
          console.warn('[ffmpeg]', msg);
        }
      });

      this.proc.on('error', (err) => {
        console.error('ffmpeg process error:', err.message);
        this.lastError = err.message;
        this.ffmpegReady = false;
      });

      this.proc.on('exit', (code, signal) => {
        console.log(`ffmpeg exited code=${code} signal=${signal}`);
        this.ffmpegReady = false;
        this.proc = null;
      });

      this._consumeStdout();
      return true;
    } catch (e) {
      this.lastError = e.message;
      console.error('Failed to start ffmpeg:', e.message);
      return false;
    }
  }

  pushH264(data) {
    if (!this.proc || !this.ffmpegReady) return false;
    try {
      const ok = this.proc.stdin.write(data);
      this.queueBytes += data.length;
      return ok;
    } catch (e) {
      this.lastError = e.message;
      return false;
    }
  }

  _consumeStdout() {
    if (!this.proc) return;
    let leftover = null;
    this.proc.stdout.on('data', (chunk) => {
      const buf = leftover ? Buffer.concat([leftover, chunk]) : chunk;
      const frames = Math.floor(buf.length / FRAME_BYTES);
      const remainder = buf.length % FRAME_BYTES;
      for (let i = 0; i < frames; i++) {
        const offset = i * FRAME_BYTES;
        const frameBuf = Buffer.from(buf.buffer, buf.byteOffset + offset, FRAME_BYTES);
        this.framesDecoded++;
        if (this.onFrame) {
          try {
            this.onFrame(frameBuf, WIDTH, HEIGHT, Date.now());
          } catch (e) {
            console.warn('onFrame error:', e.message);
          }
        }
      }
      leftover = remainder > 0 ? Buffer.from(buf.buffer, buf.byteOffset + frames * FRAME_BYTES, remainder) : null;

      const now = Date.now();
      if (now - this.lastReport >= 5000) {
        const elapsed = (now - this.startedAt) / 1000;
        console.log(`decoded ${this.framesDecoded} frames in ${elapsed.toFixed(1)}s = ${(this.framesDecoded/elapsed).toFixed(1)} fps`);
        this.framesDecoded = 0;
        this.lastReport = now;
        this.startedAt = now;
      }
    });
  }

  stop() {
    if (!this.proc) return;
    try {
      this.proc.stdin.end();
    } catch (_) {}
    try {
      this.proc.kill();
    } catch (_) {}
    this.proc = null;
    this.ffmpegReady = false;
    console.log('H264 decoder stopped');
  }
}

module.exports = { H264Decoder, WIDTH, HEIGHT, FRAME_BYTES };
