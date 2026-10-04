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
  const expected = (await status()).video;
  const image = jpeg.decode(await get('/frame.jpg'));
  assert.equal(image.width, expected.width);
  assert.equal(image.height, expected.height);
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
    profile: expected.id,
    peerAddress: previous.h264.peerAddress,
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
  for (const metric of ['decodedFps', 'jpegFps', 'renderedFps']) {
    assert.ok(summary[metric] >= expected.fps * 0.96 && summary[metric] <= expected.fps * 1.04,
      `${metric} is not near ${expected.fps}fps: ${summary[metric]}`);
  }
  assert.ok(summary.maxKbps <= expected.maxBitrateKbps, 'Measured transport exceeded profile ceiling');
  assert.equal(summary.requests, 0, 'Stream reconnected during measurement');
})().catch(error => { console.error(error); process.exitCode = 1; });
