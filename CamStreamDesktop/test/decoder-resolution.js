const assert = require('assert');
const { spawnSync } = require('child_process');
const ffmpeg = require('ffmpeg-static');
const { H264Decoder, RawFrameParser, JpegFrameParser, WIDTH, HEIGHT } = require('../h264-decoder');
const jpeg = require('jpeg-js');

function createH264(width, height) {
  const result = spawnSync(ffmpeg, [
    '-loglevel', 'error',
    '-f', 'lavfi', '-i', `testsrc2=size=${width}x${height}:rate=30`,
    '-frames:v', '60', '-pix_fmt', 'yuv420p', '-c:v', 'libx264',
    '-preset', 'ultrafast', '-tune', 'zerolatency', '-f', 'h264', 'pipe:1'
  ], { maxBuffer: 16 * 1024 * 1024 });
  assert.strictEqual(result.status, 0, result.stderr.toString());
  return result.stdout;
}

async function assertDecodedSize(width, height) {
  const decoder = new H264Decoder();
  let rawFrames = 0;
  let jpegFrames = 0;
  const encoded = createH264(width, height);
  const decoded = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Timed out decoding ${width}x${height}`)), 10000);
    const complete = () => {
      if (rawFrames === 60 && jpegFrames === 60) { clearTimeout(timeout); resolve(); }
    };
    decoder.onFrame = (frame, frameWidth, frameHeight) => {
      if (frameWidth !== WIDTH || frameHeight !== HEIGHT || frame.length !== WIDTH * HEIGHT * 4) {
        clearTimeout(timeout);
        reject(new Error(`Unexpected frame ${frameWidth}x${frameHeight} (${frame.length} bytes)`));
        return;
      }
      rawFrames++;
      complete();
    };
    decoder.onJpeg = frame => {
      if (jpegFrames === 0) {
        const decoded = jpeg.decode(frame);
        assert.equal(decoded.width, WIDTH);
        assert.equal(decoded.height, HEIGHT);
      }
      jpegFrames++;
      complete();
    };
  });

  assert.strictEqual(decoder.start(), true, decoder.lastError || 'Decoder did not start');
  decoder.proc.stdin.end(encoded);
  try {
    await decoded;
  } finally {
    decoder.stop();
  }
}

(async () => {
  const parsed = [];
  const parser = new RawFrameParser(8, frame => parsed.push(Buffer.from(frame)));
  const bytes = Buffer.from(Array.from({ length: 24 }, (_, i) => i));
  for (let i = 0; i < bytes.length; i += 3) parser.push(bytes.subarray(i, i + 3));
  assert.deepEqual(Buffer.concat(parsed), bytes);
  const jpegParsed = [];
  const jpegParser = new JpegFrameParser(frame => jpegParsed.push(frame));
  const images = Buffer.from([255, 216, 12, 255, 217, 255, 216, 22, 255, 217]);
  for (const byte of images) jpegParser.push(Buffer.from([byte]));
  assert.equal(jpegParsed.length, 2);
  assert.deepEqual(Buffer.concat(jpegParsed), images);
  await assertDecodedSize(1280, 720);
  await assertDecodedSize(640, 480);
  console.log('Native 720p decoder: 60/60 BGRA + JPEG frames, split frame boundaries: OK');
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
