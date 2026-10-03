// Measure real delivery, not just configured FPS or duplicated output frames.
// Usage: node test/live-performance.js [seconds] [port]
const assert = require('node:assert/strict');
const http = require('node:http');
const jpeg = require('jpeg-js');
const seconds = Number(process.argv[2]) || 60;
const port = Number(process.argv[3]) || 8080;
function get(url) {
  return new Promise((resolve, reject) => {
    const req = http.get(`http://127.0.0.1:${port}${url}`, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => res.statusCode === 200
        ? resolve(Buffer.concat(chunks)) : reject(new Error(`HTTP ${res.statusCode}`)));
    });
    req.setTimeout(3000, () => req.destroy(new Error('Request timed out')));
    req.on('error', reject);
  });
}
const status = async () => JSON.parse(await get('/status'));
(async () => {
  const image = jpeg.decode(await get('/frame.jpg'));
  assert.equal(image.width, 1280);
  assert.equal(image.height, 720);
  let previous = await status();
  assert.equal(previous.h264.active, true, 'Persistent Android stream must be active');
  const first = previous;
  let at = Date.now();
  const start = at;
  const samples = [];
  while (Date.now() - start < seconds * 1000) {
    await new Promise(resolve => setTimeout(resolve, 2000));
    const now = Date.now();
    const current = await status();
    const elapsed = now - at;
    const sample = {
      second: Number(((now - start) / 1000).toFixed(1)),
      decodedFps: Number(((current.h264.decoder.framesDecoded - previous.h264.decoder.framesDecoded) * 1000 / elapsed).toFixed(2)),
      jpegFps: Number(((current.h264.decoder.jpegFrames - previous.h264.decoder.jpegFrames) * 1000 / elapsed).toFixed(2)),
      renderedFps: Number(((current.h264.output.renderedFrames - previous.h264.output.renderedFrames) * 1000 / elapsed).toFixed(2)),
      kbps: Math.round((current.h264.bytes - previous.h264.bytes) * 8 / elapsed),
      queueBytes: current.h264.decoder.queueBytes,
      callbackMs: current.h264.output.callbackMs,
      rendererAckMs: current.h264.output.rendererAckMs
    };
    assert.equal(current.connected, true);
    assert.equal(current.h264.decoder.error, null);
    assert.equal(current.h264.output.virtualCamErrors, first.h264.output.virtualCamErrors);
    assert.equal(current.h264.output.ndiErrors, first.h264.output.ndiErrors);
    samples.push(sample);
    console.log(JSON.stringify(sample));
    previous = current;
    at = now;
  }
  const elapsed = at - start;
  const summary = {
    seconds: elapsed / 1000,
    resolution: `${image.width}x${image.height}`,
    decodedFps: (previous.h264.decoder.framesDecoded - first.h264.decoder.framesDecoded) * 1000 / elapsed,
    jpegFps: (previous.h264.decoder.jpegFrames - first.h264.decoder.jpegFrames) * 1000 / elapsed,
    renderedFps: (previous.h264.output.renderedFrames - first.h264.output.renderedFrames) * 1000 / elapsed,
    bitrateKbps: (previous.h264.bytes - first.h264.bytes) * 8 / elapsed,
    minKbps: Math.min(...samples.map(sample => sample.kbps)),
    maxKbps: Math.max(...samples.map(sample => sample.kbps)),
    maxQueueBytes: Math.max(...samples.map(sample => sample.queueBytes)),
    requests: previous.h264.requests - first.h264.requests
  };
  console.log('MEASURED ' + JSON.stringify(summary));
  assert.ok(summary.decodedFps >= 29 && summary.decodedFps <= 31, 'Measured camera output is not near 30fps');
  assert.ok(summary.jpegFps >= 29 && summary.jpegFps <= 31, 'Viewer delivery is not near 30fps');
  assert.ok(summary.renderedFps >= 29 && summary.renderedFps <= 31, 'Desktop renderer is not near 30fps');
  assert.ok(summary.maxKbps <= 8000, 'Measured transport exceeded the 8000kbps ceiling');
})().catch(error => { console.error(error); process.exitCode = 1; });
