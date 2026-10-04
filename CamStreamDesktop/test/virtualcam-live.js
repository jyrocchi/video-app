// Exercise the installed DirectShow filter using a real capture graph.
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const ffmpeg = require('ffmpeg-static');
const { getProfile } = require('../stream-profiles');
const profile = getProfile(process.argv[2]);
const size = `${profile.width}x${profile.height}`;
const proc = spawn(ffmpeg, [
  '-hide_banner', '-loglevel', 'warning',
  '-rtbufsize', String(profile.width * profile.height * 3 * 4),
  '-probesize', '32', '-analyzeduration', '0',
  '-f', 'dshow', '-video_size', size, '-framerate', String(profile.fps),
  '-i', 'video=JyroCam', '-frames:v', '300',
  '-c:v', 'copy', '-f', 'framemd5', 'pipe:1'
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
    assert.ok(text.includes(`#dimensions 0: ${size}`));
    assert.equal(frames, 300);
    const fps = (frames - 1) * 1000 / (lastAt - firstAt);
    console.log(JSON.stringify({ output: 'DirectShow', profile: profile.id, frames,
      uniqueHashes: hashes.size, measuredFps: fps, errors }));
    assert.ok(fps >= profile.fps * 0.96 && fps <= profile.fps * 1.04, `DirectShow delivered ${fps}fps`);
    assert.ok(hashes.size >= 290, `Only ${hashes.size} distinct frames`);
    console.log(JSON.stringify({ output: 'DirectShow', width: profile.width, height: profile.height,
      frames, uniqueHashes: hashes.size, measuredFps: fps }));
  } catch (error) { console.error(error); process.exitCode = 1; }
});
