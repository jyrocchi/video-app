// Same sRGB operations/order as the viewer's CSS brightness/contrast/saturate.
// A reusable output buffer keeps native consumers independent from decoder memory.
class ImageAdjustments {
  constructor() {
    this.values = { brightness: 100, contrast: 100, saturation: 100 };
    this.blackout = false;
    this.output = Buffer.alloc(0);
    this.lut = new Float64Array(256);
    this.set(this.values);
  }

  set(values = {}) {
    for (const key of Object.keys(this.values)) {
      if (typeof values[key] === 'number' && Number.isFinite(values[key])) {
        this.values[key] = Math.max(0, Math.min(200, values[key]));
      }
    }
    const b = this.values.brightness / 100, c = this.values.contrast / 100;
    for (let i = 0; i < 256; i++) this.lut[i] = Math.max(0, Math.min(255,
      (Math.min(255, i * b) - 127.5) * c + 127.5));
    return { ...this.values };
  }

  apply(bgra) {
    if (!this.blackout && Object.values(this.values).every(v => v === 100)) return bgra;
    if (this.output.length !== bgra.length) this.output = Buffer.alloc(bgra.length);
    const out = this.output;
    if (this.blackout) {
      out.fill(0);
      for (let i = 3; i < out.length; i += 4) out[i] = 255;
      return out;
    }
    const s = this.values.saturation / 100, lut = this.lut;
    for (let i = 0; i < bgra.length; i += 4) {
      const b = lut[bgra[i]], g = lut[bgra[i + 1]], r = lut[bgra[i + 2]];
      const gray = .213 * r + .715 * g + .072 * b;
      out[i] = Math.round(Math.max(0, Math.min(255, gray + s * (b - gray))));
      out[i + 1] = Math.round(Math.max(0, Math.min(255, gray + s * (g - gray))));
      out[i + 2] = Math.round(Math.max(0, Math.min(255, gray + s * (r - gray))));
      out[i + 3] = bgra[i + 3];
    }
    return out;
  }
}
module.exports = { ImageAdjustments };
