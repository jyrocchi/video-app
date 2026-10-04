const assert = require('assert');
const { ImageAdjustments } = require('../image-adjustments');

const adjustments = new ImageAdjustments();
const red = Buffer.from([0, 0, 255, 255]); // BGRA
assert.strictEqual(adjustments.apply(red), red, 'neutral settings should avoid a copy');

adjustments.set({ saturation: 0 });
const gray = adjustments.apply(red);
assert.deepStrictEqual([...gray], [54, 54, 54, 255], 'zero saturation should produce luminance gray');

adjustments.set({ brightness: 100, contrast: 0, saturation: 100 });
assert.deepStrictEqual([...adjustments.apply(red)], [128, 128, 128, 255], 'zero contrast should produce mid-gray');

adjustments.set({ brightness: 200, contrast: 100, saturation: 100 });
assert.deepStrictEqual([...adjustments.apply(red)], [0, 0, 255, 255], 'brightness should clamp at white');

adjustments.blackout = true;
assert.deepStrictEqual([...adjustments.apply(red)], [0, 0, 0, 255], 'blackout should output opaque black');
adjustments.blackout = false;
assert.deepStrictEqual([...adjustments.apply(red)], [0, 0, 255, 255], 'disabling blackout should restore adjusted source');

assert.deepStrictEqual(adjustments.set({ brightness: -5, contrast: 500, saturation: NaN }),
  { brightness: 0, contrast: 200, saturation: 100 }, 'adjustments should clamp to supported range');

console.log('Image brightness/contrast/saturation and blackout: OK');
