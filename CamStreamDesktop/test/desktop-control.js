// Local test harness; launch the desktop with --remote-debugging-port=9222.
// Usage: node test/desktop-control.js info|install-camera|record|stop-record
const action = process.argv[2] || 'info';
const expressions = {
  info: 'window.api.getInfo()',
  inspect: `({ready: document.readyState, visible: document.visibilityState,
    connection: document.getElementById('connText').textContent,
    width: document.getElementById('frame').naturalWidth,
    height: document.getElementById('frame').naturalHeight,
    src: document.getElementById('frame').src.slice(0, 40)})`,
  'install-camera': 'window.api.setVirtualCamera(true)',
  record: 'window.api.startRecording({fps:30})',
  'stop-record': 'window.api.stopRecording()'
};
(async () => {
  if (!expressions[action]) throw new Error(`Unknown action: ${action}`);
  const pages = await (await fetch('http://127.0.0.1:9222/json/list')).json();
  const page = pages.find(page => page.type === 'page' && page.url.includes('viewer.html'));
  if (!page) throw new Error('JyroCam renderer not found');
  const socket = new WebSocket(page.webSocketDebuggerUrl);
  const timeout = setTimeout(() => { socket.close(); process.exitCode = 1; }, 10000);
  socket.addEventListener('open', () => socket.send(JSON.stringify({
    id: 1, method: 'Runtime.evaluate',
    params: { expression: expressions[action], awaitPromise: true, returnByValue: true }
  })));
  socket.addEventListener('message', event => {
    const reply = JSON.parse(event.data);
    if (reply.id !== 1) return;
    clearTimeout(timeout);
    socket.close();
    if (reply.error || reply.result?.exceptionDetails) {
      console.error(JSON.stringify(reply));
      process.exitCode = 1;
    } else {
      console.log(JSON.stringify(reply.result.result.value));
    }
  });
})().catch(error => { console.error(error); process.exitCode = 1; });
