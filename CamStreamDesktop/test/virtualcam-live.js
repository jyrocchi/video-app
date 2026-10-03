// Exercise the installed DirectShow filter using a real capture graph.
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const ffmpeg = require('ffmpeg-static');
const proc = spawn(ffmpeg, [
  '-hide_banner', '-loglevel', 'warning',
  '-f', 'dshow', '-video_size', '1280x720', '-framerate', '30',
  '-i', 'video=JyroCam', '-frames:v', '300',
  '-fps_mode', 'passthrough', '-f', 'framemd5', 'pipe:1'
], { windowsHide: true });
let errors = '';
let text = '';
let pending = '';
let frames = 0;
let firstAt = 0;
let lastAt = 0;
const hashes = new Set();
const timer = setTimeout(() => proc.kill(), 20000);
proc.stderr.on('data', data => { errors = (errors + data).slice(-4000); });
proc.stdout.on('data', data => {
  text += data;
  pending += data;
  const lines = pending.split('\n');
  pending = lines.pop();
  for (const line of lines) {
    if (!/^0,/.test(line)) continue;
    const now = Date.now();
    if (!firstAt) firstAt = now;
    lastAt = now;
    frames++;
    hashes.add(line.split(',').pop().trim());
  }
});
proc.on('error', error => { console.error(error); process.exitCode = 1; });
proc.on('close', code => {
  clearTimeout(timer);
  try {
    assert.equal(code, 0, `${frames} frames received. ${errors}`);
    assert.match(text, /#dimensions 0: 1280x720/);
    assert.equal(frames, 300);
    const fps = (frames - 1) * 1000 / (lastAt - firstAt);
    assert.ok(fps >= 29 && fps <= 31, `DirectShow delivered ${fps}fps`);
    console.log(JSON.stringify({ output: 'DirectShow', width: 1280, height: 720,
      frames, uniqueHashes: hashes.size, measuredFps: fps }));
  } catch (error) { console.error(error); process.exitCode = 1; }
});
