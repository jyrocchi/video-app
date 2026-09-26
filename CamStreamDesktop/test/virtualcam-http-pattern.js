const http = require('node:http');
const jpeg = require('jpeg-js');

const width = 640;
const height = 480;
const colors = [
  [255, 0, 0, 255], [255, 255, 0, 255], [0, 255, 0, 255],
  [0, 255, 255, 255], [0, 0, 255, 255], [255, 0, 255, 255],
  [255, 255, 255, 255]
];
const rgba = Buffer.alloc(width * height * 4);
for (let y = 0; y < height; y++) {
  for (let x = 0; x < width; x++) {
    const color = colors[Math.floor(x * colors.length / width)];
    const offset = (y * width + x) * 4;
    rgba.set(color, offset);
  }
}
const frame = jpeg.encode({ data: rgba, width, height }, 90).data;
const request = http.request('http://127.0.0.1:8080/upload', {
  method: 'POST',
  headers: { 'Content-Type': 'image/jpeg', 'Content-Length': frame.length }
}, response => {
  response.resume();
  response.on('end', () => {
    if (response.statusCode !== 200) process.exitCode = 1;
    else console.log('Uploaded test pattern to desktop frame pipeline');
  });
});
request.on('error', error => { console.error(error); process.exitCode = 1; });
request.end(frame);
