const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = process.env.PORT || 8080;

let latestFrame = null;
let latestFrameB64 = null;
let latestFrameTime = 0;
const sseClients = new Set();

function getLocalIPs() {
  const ifaces = os.networkInterfaces();
  const ips = [];
  for (const name of Object.keys(ifaces)) {
    for (const iface of ifaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        ips.push(iface.address);
      }
    }
  }
  return ips;
}

function sendFile(res, filePath, contentType) {
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': contentType });
    res.end(data);
  });
}

function broadcastFrame(frameB64) {
  for (const client of sseClients) {
    try {
      client.write(`data: ${frameB64}\n\n`);
    } catch (_) {
      sseClients.delete(client);
    }
  }
}

const server = http.createServer((req, res) => {
  const url = req.url.split('?')[0];

  if (url === '/' || url === '/viewer' || url === '/viewer.html') {
    sendFile(res, path.join(__dirname, 'viewer.html'), 'text/html; charset=utf-8');
    return;
  }
  if (url === '/phone' || url === '/phone.html') {
    sendFile(res, path.join(__dirname, 'phone.html'), 'text/html; charset=utf-8');
    return;
  }

  if (url === '/upload' && req.method === 'POST') {
    const chunks = [];
    let total = 0;
    const limit = 8 * 1024 * 1024;
    req.on('data', (c) => {
      total += c.length;
      if (total > limit) {
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      try {
        const body = Buffer.concat(chunks);
        const ct = (req.headers['content-type'] || '').toLowerCase();
        if (ct.includes('image/jpeg') || ct.includes('application/octet-stream')) {
          latestFrame = body;
          latestFrameB64 = body.toString('base64');
        } else {
          const text = body.toString('utf8');
          const match = text.match(/^data:image\/\w+;base64,(.+)$/);
          if (!match) { res.writeHead(400); res.end('bad data'); return; }
          latestFrameB64 = match[1];
          latestFrame = Buffer.from(match[1], 'base64');
        }
        latestFrameTime = Date.now();
        broadcastFrame(latestFrameB64);
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.end('ok');
      } catch (e) {
        res.writeHead(500); res.end('err');
      }
    });
    return;
  }

  if (url === '/frame.jpg' && req.method === 'GET') {
    if (!latestFrame) { res.writeHead(404); res.end('no frame'); return; }
    res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store' });
    res.end(latestFrame);
    return;
  }

  if (url === '/stream') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*'
    });
    res.write(': connected\n\n');
    if (latestFrameB64) res.write(`data: ${latestFrameB64}\n\n`);
    sseClients.add(res);
    const ka = setInterval(() => {
      try { res.write(': ka\n\n'); } catch (_) {}
    }, 15000);
    req.on('close', () => {
      clearInterval(ka);
      sseClients.delete(res);
    });
    return;
  }

  if (url === '/status') {
    const data = JSON.stringify({
      connected: !!latestFrame,
      lastFrameAge: latestFrame ? Math.floor((Date.now() - latestFrameTime) / 1000) : null,
      clients: sseClients.size
    });
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(data);
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('Not found');
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('=====================================');
  console.log('  Camara Stream Server');
  console.log('=====================================');
  console.log(`  Visor (PC):    http://localhost:${PORT}/`);
  const ips = getLocalIPs();
  for (const ip of ips) {
    console.log(`  Visor (red):   http://${ip}:${PORT}/`);
    console.log(`  Celular:       http://${ip}:${PORT}/phone`);
  }
  console.log('=====================================');
});