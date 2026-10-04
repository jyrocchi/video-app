// Local test harness; launch the desktop with --remote-debugging-port=9222.
// Usage: node test/desktop-control.js info|inspect|image-adjustments|ndi-status|install-camera|record|stop-record
const action = process.argv[2] || 'info';
const expressions = {
  info: 'window.api.getInfo()',
  inspect: `({ready: document.readyState, visible: document.visibilityState,
    connection: document.getElementById('connText').textContent,
    width: document.getElementById('frame').naturalWidth,
    height: document.getElementById('frame').naturalHeight,
    src: document.getElementById('frame').src.slice(0, 40)})`,
  'image-adjustments': `(async () => {
    const button = document.getElementById('imageAdjustBtn');
    const panel = document.getElementById('imageAdjustPanel');
    const image = document.getElementById('frame');
    if (!CSS.supports('filter', 'brightness(100%) contrast(100%) saturate(100%)')) throw new Error('CSS image filters are unsupported');
    button.click();
    if (panel.hidden || button.getAttribute('aria-expanded') !== 'true') throw new Error('Adjustment panel did not open');
    for (const [id, value] of [['brightnessSlider', 125], ['contrastSlider', 80], ['saturationSlider', 150]]) {
      const slider = document.getElementById(id);
      slider.value = String(value);
      slider.dispatchEvent(new Event('input', { bubbles: true }));
    }
    const filter = image.style.filter;
    if (filter !== 'brightness(125%) contrast(80%) saturate(150%)') throw new Error('Unexpected image filter: ' + filter);
    const mainValues = await window.api.setImageAdjustments({ brightness: 125, contrast: 80, saturation: 150 });
    if (mainValues.brightness !== 125 || mainValues.contrast !== 80 || mainValues.saturation !== 150) throw new Error('Main-process adjustments were not updated');
    const blackout = await window.api.setBlackout(true);
    if (!blackout.blackout) throw new Error('Blackout IPC did not enable output blackout');
    await window.api.setBlackout(false);
    const saved = JSON.parse(localStorage.getItem('jyrocam-image-adjustments'));
    if (saved.brightness !== 125 || saved.contrast !== 80 || saved.saturation !== 150) throw new Error('Image adjustments were not persisted');
    document.getElementById('resetImageAdjustments').click();
    if (image.style.filter !== 'brightness(100%) contrast(100%) saturate(100%)') throw new Error('Reset did not restore neutral filter');
    button.click();
    if (!panel.hidden || button.getAttribute('aria-expanded') !== 'false') throw new Error('Adjustment panel did not close');
    return { filter, resetFilter: image.style.filter, panelClosed: panel.hidden, saved };
  })()`,
  'ndi-status': `(async () => {
    const toggle = document.getElementById('ndiToggle');
    const status = document.getElementById('ndiStatus');
    const original = await window.api.getInfo();
    updateNdiStatus({ ndi: false, ndiAvailable: false, ndiAvailabilityError: 'runtime ausente' });
    const unavailable = { checked: toggle.checked, disabled: toggle.disabled, text: status.textContent };
    updateNdiStatus({ ndi: false, ndiAvailable: true, ndiError: null });
    const off = { checked: toggle.checked, disabled: toggle.disabled, text: status.textContent };
    updateNdiStatus({ ndi: true, ndiAvailable: true, ndiError: null });
    const on = { checked: toggle.checked, disabled: toggle.disabled, text: status.textContent };
    updateNdiStatus({ ndi: false, ndiAvailable: true, ndiError: 'test error' });
    const retry = { checked: toggle.checked, disabled: toggle.disabled, text: status.textContent };
    updateNdiStatus(original);
    if (!unavailable.disabled || unavailable.text !== 'NDI no disponible') throw new Error('NDI unavailable state is incorrect');
    if (off.disabled || off.text !== 'Desactivado') throw new Error('NDI available/off state is incorrect');
    if (!on.checked || on.text !== 'Activo') throw new Error('NDI active state is incorrect');
    if (retry.disabled || retry.text !== 'Error: reintentar') throw new Error('NDI retry state is incorrect');
    return { unavailable, off, on, retry };
  })()`,
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
