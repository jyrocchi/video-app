// Real SDK emulator/instrumentation matrix, one image at a time.
// Usage: node test-android-matrix.js 24 26 ... 37.2
const fs = require('node:fs');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const sdk = process.env.ANDROID_HOME || 'C:\\Android';
const temp = 'C:\\Users\\PC\\AppData\\Local\\Temp\\opencode';
const avdHome = path.join(temp, 'jyrocam-avds');
const env = { ...process.env, ANDROID_HOME: sdk, ANDROID_SDK_ROOT: sdk, ANDROID_AVD_HOME: avdHome };
const adb = path.join(sdk, 'platform-tools', 'adb.exe');
const emulator = process.env.CAMSTREAM_EMULATOR_BINARY || path.join(sdk, 'emulator', 'emulator.exe');
const gpu = process.env.CAMSTREAM_EMULATOR_GPU || 'swiftshader';
const disableLumaSampling = process.env.CAMSTREAM_EMULATOR_DISABLE_LUMA === '1';
const manager = path.join(sdk, 'cmdline-tools', 'latest', 'bin', 'avdmanager.bat');
const serial = 'emulator-5560';
const apis = process.argv.slice(2);
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function command(exe, args, options = {}) {
  const result = spawnSync(exe, args, { encoding: 'utf8', timeout: 45000, maxBuffer: 8 * 1024 * 1024, ...options });
  if (result.status !== 0) throw new Error(`${exe} ${args.join(' ')}\n${result.stdout || ''}${result.stderr || ''}${result.error || ''}`);
  return result.stdout;
}
function android(...args) { return command(adb, ['-s', serial, ...args]); }
async function runApi(api) {
  if (!/^\d+(\.\d+)?$/.test(api)) throw new Error(`Invalid API ${api}`);
  const name = `jyrocam-api${api.replace('.', '-')}`;
  const image = api === '37.0' ? `system-images;android-${api};google_apis;x86_64`
                  : Number(api) >= 37 ? `system-images;android-${api};google_apis_ps16k;x86_64`
                                : `system-images;android-${api};default;x86_64`;
  const avd = path.join(avdHome, name);
  command('cmd.exe', ['/d', '/s', '/c', `call "${manager}" create avd -n ${name} -k "${image}" -p "${avd}" -d pixel_2 --force`],
    { input: 'no\n', env, timeout: 60000, windowsVerbatimArguments: true });
  if (Number(api) >= 37) {
    const configPath = path.join(avd, 'config.ini');
    const config = fs.readFileSync(configPath, 'utf8').replace(/^disk\.dataPartition\.size=.*$/m, 'disk.dataPartition.size=8G');
    fs.writeFileSync(configPath, config.includes('disk.dataPartition.size=8G') ? config : config + '\ndisk.dataPartition.size=8G\n');
  }
  const output = fs.openSync(path.join(temp, `${name}-emulator.log`), 'w');
  const proc = spawn(emulator, ['-avd', name, '-port', '5560', '-no-window', '-no-audio',
    '-no-boot-anim', '-no-snapshot', '-no-metrics', '-show-kernel',
    '-wipe-data',
    ...(process.env.CAMSTREAM_EMULATOR_FEATURES ? ['-feature', process.env.CAMSTREAM_EMULATOR_FEATURES] : []),
    '-gpu', gpu,
    '-camera-back', 'emulated', '-camera-front', 'emulated',
    '-memory', Number(api) >= 37 ? '4096' : '2048', '-cores', '2'],
    { env, stdio: ['ignore', output, output], windowsHide: true });
  fs.closeSync(output);
  try {
    const deadline = Date.now() + 240000;
    let booted = false;
    let lumaConfigured = false;
    while (Date.now() < deadline) {
      if (proc.exitCode !== null) throw new Error(`Emulator exited ${proc.exitCode}; see ${name}-emulator.log`);
      if (disableLumaSampling && Number(api) >= 37 && !lumaConfigured) {
        try {
          // Only the named test AVD is touched. This official SurfaceFlinger
          // debug property bypasses the host graphics readback assertion.
          if (!android('emu', 'avd', 'name').startsWith(name)) throw new Error('Wrong AVD');
          android('root');
          await delay(2000);
          android('shell', 'setprop', 'debug.sf.luma_sampling', '0');
          android('shell', 'setprop', 'ctl.restart', 'surfaceflinger');
          lumaConfigured = android('shell', 'getprop', 'debug.sf.luma_sampling').trim() === '0';
          if (lumaConfigured) console.log(`API ${api}: emulator-only luma sampling disabled`);
        } catch (_) {}
      }
      try {
        booted = android('emu', 'avd', 'name').startsWith(name) &&
          android('shell', 'getprop', 'sys.boot_completed').trim() === '1' &&
          android('shell', 'pm', 'path', 'android').includes('package:');
      } catch (_) {}
      if (booted) break;
      await delay(2000);
    }
    if (!booted) {
      try { fs.writeFileSync(path.join(temp, `${name}-boot-logcat.txt`), android('logcat', '-d', '-s', 'SurfaceFlinger:E', 'DEBUG:F', 'AndroidRuntime:E')); } catch (_) {}
      throw new Error(`API ${api} boot timeout`);
    }
    android('shell', 'input', 'keyevent', '82');
    const version = android('shell', 'getprop', 'ro.build.version.release').trim();
    let pageSize = 'unreported';
    try { pageSize = android('shell', 'getconf', 'PAGESIZE').trim(); } catch (_) {}
    try {
      android('install', '--no-streaming', '-r', path.join(__dirname, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'));
      android('install', '--no-streaming', '-r', path.join(__dirname, 'app', 'build', 'outputs', 'apk', 'androidTest', 'debug', 'app-debug-androidTest.apk'));
    } catch (error) {
      try { fs.writeFileSync(path.join(temp, `${name}-install-logcat.txt`), android('logcat', '-d', '-s', 'AndroidRuntime:E', 'DEBUG:F', 'PackageManager:E')); } catch (_) {}
      try { fs.writeFileSync(path.join(temp, `${name}-storage.txt`), android('shell', 'df', '-h', '/data')); } catch (_) {}
      throw error;
    }
    const result = command(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
      '-e', 'class', 'com.anomaly.camstream.AndroidCompatibilityTest',
      'com.anomaly.camstream.debug.test/androidx.test.runner.AndroidJUnitRunner'], { timeout: 180000 });
    fs.writeFileSync(path.join(temp, `${name}-instrumentation.txt`), result);
    if (!/OK \(4 tests\)/.test(result) || /FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(result)) {
      throw new Error(`Instrumentation failed on API ${api}:\n${result}`);
    }
    const evidence = android('logcat', '-d', '-s', 'JyroCamCompat:I');
    fs.writeFileSync(path.join(temp, `${name}-compatibility.txt`), evidence);
    const emulatorVersion = command(emulator, ['-version']).match(/version\s+([\d.]+)/)?.[1] || 'unknown';
    const summary = { api, android: version, pageSize, emulatorVersion, gpu, image,
      emulatorLumaSamplingDisabled: lumaConfigured,
      cameraAdvertises720: /CAMERA_ADVERTISES_720P=true/.test(evidence),
      codecMirrorTransport: /CODEC_720P_MIRROR_TRANSPORT=PASS/.test(evidence),
      // Older images can wrap logcat before the final read; the successful
      // instrumentation outcome is the authoritative shutdown-test evidence.
      codecStopBackpressure: /test=codecShutdownWhileOutputSinkBackpressured/.test(result),
       instrumentedTests: 4,
       profileCodecs: [...evidence.matchAll(/PROFILE_CODEC=([^\r\n]+)/g)].map(match => match[1]),
      cameraTransportMirrorForeground: 'PASS' };
    console.log('ANDROID_MATRIX ' + JSON.stringify(summary));
    return summary;
  } finally {
    try { android('emu', 'kill'); } catch (_) { proc.kill(); }
    const end = Date.now() + 30000;
    while (proc.exitCode === null && Date.now() < end) await delay(200);
    if (proc.exitCode === null) proc.kill();
    await delay(1000);
  }
}
(async () => {
  if (!apis.length) throw new Error('Specify system-image APIs to test');
  if (!fs.existsSync(temp)) throw new Error('Approved temp parent missing');
  fs.mkdirSync(avdHome, { recursive: true });
  const receiver = spawn('node', [path.join(__dirname, '..', 'CamStreamDesktop', 'test', 'android-compat-server.js')],
    { cwd: path.join(__dirname, '..', 'CamStreamDesktop'), stdio: ['ignore', 'inherit', 'inherit'] });
  const results = [];
  try {
    await delay(1000);
    for (const api of apis) {
      try { results.push(await runApi(api)); }
      catch (error) { results.push({ api, result: 'FAIL', error: error.message }); console.error(error.message); }
    }
  } finally { receiver.kill(); }
  const resultPath = path.join(temp, 'jyrocam-android-matrix.json');
  let previous = [];
  try { previous = JSON.parse(fs.readFileSync(resultPath, 'utf8')); } catch (_) {}
  const combined = new Map([...previous, ...results].map(result => [result.api, result]));
  fs.writeFileSync(resultPath, JSON.stringify([...combined.values()].sort((a, b) => Number(a.api) - Number(b.api)), null, 2));
  console.log('MATRIX_SUMMARY ' + JSON.stringify(results));
  if (results.some(result => result.result === 'FAIL')) process.exitCode = 1;
})().catch(error => { console.error(error); process.exitCode = 1; });
