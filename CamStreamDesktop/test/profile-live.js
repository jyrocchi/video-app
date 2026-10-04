// Requires the real desktop at 8080 and the debug APK + instrumentation installed.
// Usage: node test/profile-live.js 720p60 usb|wifi [mirror] [seconds]
const { spawn, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { getProfile } = require('../stream-profiles');
const [id = '720p30', transport = 'usb', mirror = 'false', seconds = '60'] = process.argv.slice(2);
getProfile(id);
assert.ok(['usb', 'wifi'].includes(transport));
const adb = process.env.ADB || 'C:\\Android\\platform-tools\\adb.exe';
const serial = process.env.ANDROID_SERIAL || 'ZY22G6BQKF';
const destination = transport === 'usb' ? '127.0.0.1' : (process.env.JYROCAM_LAN_IP || '192.168.1.81');
const folder = path.join(process.env.TEMP, 'opencode');
assert.ok(fs.existsSync(folder));
const prefix = path.join(folder, `jyrocam-${id}-${transport}-mirror-${mirror}`);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function status() { return (await fetch('http://127.0.0.1:8080/status')).json(); }
(async () => {
  if (transport === 'usb') {
    assert.equal(spawnSync(adb, ['-s', serial, 'reverse', 'tcp:8080', 'tcp:8080']).status, 0);
  }
  const child = spawn(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'class', 'com.anomaly.camstream.ProfileStreamingTest', '-e', 'profile', id,
    '-e', 'serverUrl', `http://${destination}:8080`, '-e', 'seconds', String(Number(seconds) + 20),
    '-e', 'mirror', mirror, 'com.anomaly.camstream.debug.test/androidx.test.runner.AndroidJUnitRunner']);
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; });
  child.stderr.on('data', chunk => { output += chunk; });
  let finished = false;
  const done = new Promise(resolve => child.once('exit', code => { finished = true; resolve(code); }));
  try {
    let ready = false;
    for (let i = 0; i < 60 && !finished; i++) {
      const current = await status();
      if (current.h264.active && current.video.id === id && current.h264.decoder.framesDecoded > 90) {
        assert.equal(current.h264.peerAddress === '127.0.0.1', transport === 'usb', 'Wrong network path');
        ready = true; break;
      }
      await delay(1000);
    }
    assert.ok(ready, `Stream did not start: ${output}`);
    await delay(4000);
    if (process.env.JYROCAM_TEST_DIRECTSHOW === '1') {
      const capture = spawn(process.execPath, [path.join(__dirname, 'virtualcam-live.js'), id]);
      let evidence = '';
      capture.stdout.on('data', chunk => { evidence += chunk; });
      capture.stderr.on('data', chunk => { evidence += chunk; });
      const captureCode = await new Promise(resolve => capture.once('exit', resolve));
      fs.writeFileSync(prefix + '-directshow.log', evidence);
      console.log(evidence);
      assert.equal(captureCode, 0, 'DirectShow capture failed');
    }
    const measure = spawn(process.execPath, [path.join(__dirname, 'live-performance.js'), seconds]);
    let measurements = '';
    measure.stdout.on('data', chunk => { measurements += chunk; process.stdout.write(chunk); });
    measure.stderr.on('data', chunk => { measurements += chunk; process.stderr.write(chunk); });
    const code = await new Promise(resolve => measure.once('exit', resolve));
    fs.writeFileSync(prefix + '.log', measurements);
    const summary = measurements.match(/MEASURED (.+)/)?.[1];
    if (summary) fs.writeFileSync(prefix + '.json', JSON.stringify({ ...JSON.parse(summary), transport, mirror, passed: code === 0 }, null, 2));
    assert.equal(code, 0, 'Live performance assertions failed');
  } finally {
    await done;
    fs.writeFileSync(prefix + '-instrumentation.log', output);
    const logs = spawnSync(adb, ['-s', serial, 'logcat', '-d', '-s', 'JyroCamProfiles:I', 'StreamService:I', 'H264Encoder:I', '*:S'], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
    fs.writeFileSync(prefix + '-android.log', logs.stdout || '');
    console.log(output);
  }
  assert.ok(output.includes('OK (1 test)'), 'Instrumentation failed');
})().catch(error => { console.error(error); process.exitCode = 1; });
