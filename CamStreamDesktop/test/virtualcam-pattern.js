const VirtualCamWriter = require('../virtual-cam-writer');

const writer = new VirtualCamWriter();
if (!writer.open()) process.exit(1);

const width = 640;
const height = 480;
const frame = Buffer.alloc(width * height * 4);
const colors = [
  [255, 0, 0, 255], [255, 255, 0, 255], [0, 255, 0, 255],
  [0, 255, 255, 255], [0, 0, 255, 255], [255, 0, 255, 255],
  [255, 255, 255, 255]
];
for (let y = 0; y < height; y++) {
  for (let x = 0; x < width; x++) {
    const color = colors[Math.floor(x * colors.length / width)];
    const offset = (y * width + x) * 4;
    frame[offset] = color[0];
    frame[offset + 1] = color[1];
    frame[offset + 2] = color[2];
    frame[offset + 3] = color[3];
  }
}
setInterval(() => writer.writeBgra(frame, width, height), 33);
