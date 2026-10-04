const assert = require('node:assert/strict');
const Module = require('node:module');

const originalLoad = Module._load;
Module._load = function (request, parent, isMain) {
  if (request === 'koffi') return {};
  return originalLoad.call(this, request, parent, isMain);
};
const VirtualCamWriter = require('../virtual-cam-writer');
Module._load = originalLoad;

const width = 1280;
const height = 720;
const offset = 32;
const rgbBytes = width * height * 3;
const rgbaBytes = width * height * 4;

function makeWriter() {
  const writer = Object.create(VirtualCamWriter.prototype);
  writer.opened = true;
  writer.staging = Buffer.alloc(offset + 1920 * 1080 * 3 + 4);
  writer.writeHeader();
  const mapped = Buffer.alloc(writer.staging.length);
  writer.view = 0x10000n;
  writer.headerSnapshot = Buffer.alloc(32);
  writer.RtlMoveMemory = (destination, source, size) => source.copy(
    typeof destination === 'bigint' ? mapped.subarray(Number(destination - 0x10000n)) : destination, 0, 0, size);
  return writer;
}

function assertTopLeftRedWrittenAsBottomUpBgr(writer) {
  // A red pixel at the top-left must be BGR [0, 0, 255] in the last DIB row.
  const bottomUpTopLeft = offset + ((height - 1) * width) * 3;
  assert.deepEqual([...writer.staging.subarray(bottomUpTopLeft, bottomUpTopLeft + 3)], [0, 0, 255]);
}

const bgra = Buffer.alloc(rgbaBytes);
bgra.set([0, 0, 255, 255], 0);
const bgraWriter = makeWriter();
assert.equal(bgraWriter.writeBgra(bgra, width, height), true);
assertTopLeftRedWrittenAsBottomUpBgr(bgraWriter);

const rgba = Buffer.alloc(rgbaBytes);
rgba.set([255, 0, 0, 255], 0);
const rgbaWriter = makeWriter();
assert.equal(rgbaWriter.write(rgba, width, height), true);
assertTopLeftRedWrittenAsBottomUpBgr(rgbaWriter);

const connectionHeader = Buffer.alloc(32);
const statusWriter = Object.create(VirtualCamWriter.prototype);
statusWriter.opened = true;
statusWriter.view = connectionHeader;
statusWriter.headerSnapshot = Buffer.alloc(32);
statusWriter.RtlMoveMemory = (destination, source, size) => source.copy(destination, 0, 0, size);
connectionHeader.writeUInt32LE(1, 16);
assert.equal(statusWriter.isConnected(), true);
connectionHeader.writeUInt32LE(0, 16);
assert.equal(statusWriter.isConnected(), false);

const sharedFrameSize = 32 + 1920 * 1080 * 3 + 4;
const mappedFrame = Buffer.alloc(sharedFrameSize);
const mappedWriter = Object.create(VirtualCamWriter.prototype);
mappedWriter.view = 0x10000n;
mappedWriter.staging = Buffer.alloc(sharedFrameSize);
mappedWriter.headerSnapshot = Buffer.alloc(32);
mappedWriter.RtlMoveMemory = (destination, source, size) => source.copy(
  typeof destination === 'bigint' ? mappedFrame.subarray(Number(destination - 0x10000n)) : destination, 0, 0, size);
mappedFrame.writeUInt32LE(1, 16);
mappedWriter.flushToSharedMemory();
assert.equal(mappedFrame.readUInt32LE(16), 1, 'Publishing a frame must preserve DirectShow connection state');

console.log('Virtual camera color, orientation and connection state: OK');

for (const [w, h, fps] of [[1280, 720, 60], [1920, 1080, 30], [1280, 720, 30]]) {
  const pixels = Buffer.alloc(w * h * 4);
  pixels.set([0, 0, 255, 255]);
  assert.equal(bgraWriter.writeBgra(pixels, w, h, fps), true);
  assert.equal(bgraWriter.staging.readUInt32LE(4), w);
  assert.equal(bgraWriter.staging.readUInt32LE(8), h);
  assert.equal(bgraWriter.staging.readUInt32LE(28), w * h * 3);
  assert.equal(bgraWriter.staging.readUInt32LE(32 + 1920 * 1080 * 3), fps);
  assert.deepEqual([...bgraWriter.staging.subarray(32 + (h - 1) * w * 3, 35 + (h - 1) * w * 3)], [0, 0, 255]);
}
assert.equal(bgraWriter.writeBgra(Buffer.alloc(4), 1920, 1080, 60), false);
