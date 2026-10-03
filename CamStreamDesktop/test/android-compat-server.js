// Isolated H.264 receiver for emulator instrumentation. Does not occupy port 8080
// or mix virtual camera frames with the real Motorola Wi-Fi benchmark.
const http = require('node:http');
const { H264Decoder } = require('../h264-decoder');
const decoder = new H264Decoder();
let frames = 0;
let nals = 0;
let lastFrameAt = 0;
const failedProbes = new Set();
decoder.onFrame = () => { frames++; lastFrameAt = Date.now(); };
if (!decoder.start()) throw new Error(decoder.lastError);
const server = http.createServer((req, res) => {
  let pathname = req.url;
  const retryPath = /^\/(probe-retry-[^/]+)(\/.*)$/.exec(pathname);
  if (retryPath) {
    pathname = retryPath[2];
    if (pathname === '/status' && !failedProbes.has(retryPath[1])) {
      failedProbes.add(retryPath[1]);
      res.writeHead(503);
      res.end('Transient capability probe failure');
      return;
    }
  }
  if (pathname === '/status') {
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ connected: Date.now() - lastFrameAt < 5000,
      lastFrameAge: lastFrameAt ? (Date.now() - lastFrameAt) / 1000 : 999,
      h264: { streamingSupported: true, nals, decoder: decoder.getStats() }, frames }));
    return;
  }
  if (pathname !== '/stream-h264' || req.method !== 'POST') { res.writeHead(404); res.end(); return; }
  if (!decoder.ffmpegReady) { decoder.stop(); decoder.start(); }
  let pending = Buffer.alloc(0);
  req.on('data', chunk => {
    pending = pending.length ? Buffer.concat([pending, chunk]) : chunk;
    while (pending.length >= 4) {
      const size = pending.readUInt32BE(0);
      if (!size || size > 4 * 1024 * 1024) { req.destroy(); return; }
      if (pending.length < size + 4) break;
      const nal = pending.subarray(4, size + 4);
      pending = pending.subarray(size + 4);
      nals++;
      if (!decoder.pushH264(nal)) {
        req.pause();
        decoder.proc?.stdin.once('drain', () => req.resume());
      }
    }
  });
  req.on('end', () => res.end('ok'));
  req.on('error', () => {});
});
server.requestTimeout = 0;
server.listen(18081, '0.0.0.0', () => console.log('Android compatibility receiver on 18081'));
process.on('SIGTERM', () => { decoder.stop(); server.close(); process.exit(0); });
