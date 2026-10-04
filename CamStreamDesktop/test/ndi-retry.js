const assert = require('assert');
const { NdiSender, getNdiAvailability } = require('../ndi-sender');
const { ImageAdjustments } = require('../image-adjustments');

const availability = getNdiAvailability();
assert.strictEqual(typeof availability.available, 'boolean');
if (availability.available) {
  const controls = new ImageAdjustments();
  controls.set({ saturation: 0 });
  const red = Buffer.from([0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255]);
  const gray = Buffer.from(controls.apply(red));
  const black = (() => { controls.blackout = true; return controls.apply(red); })();
  controls.blackout = false;
  for (let i = 0; i < 3; i++) {
    const sender = new NdiSender(`JyroCam-ReactivationTest-${i}`);
    sender.sendBgra(gray, 2, 2, 30);
    assert.deepStrictEqual([...sender.bgraBuffers[0].subarray(0, 4)], [...gray.subarray(0, 4)]);
    sender.sendBgra(black, 2, 2, 30);
    assert.deepStrictEqual([...sender.bgraBuffers[1].subarray(0, 4)], [0, 0, 0, 255]);
    sender.close();
  }
  console.log('NDI available; create/send/close/recreate x3: OK');
} else {
  const retry = getNdiAvailability();
  assert.strictEqual(retry.available, false);
  assert.ok(retry.error);
  console.log('NDI runtime absent; availability reports retryable state: OK');
}
