const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  startRecording: (opts) => ipcRenderer.invoke('start-recording', opts),
  stopRecording: () => ipcRenderer.invoke('stop-recording'),
  getInfo: () => ipcRenderer.invoke('get-info'),
  openFolder: () => ipcRenderer.invoke('open-folder'),
  toggleNdi: () => ipcRenderer.invoke('toggle-ndi'),
  setVirtualCamera: (install) => ipcRenderer.invoke('set-virtual-camera', install),
  setBlackout: (on) => ipcRenderer.invoke('set-blackout', !!on),
  frameRendered: () => ipcRenderer.send('frame-rendered'),
  onEvent: (cb) => {
    const listener = (_e, payload) => cb(payload);
    ipcRenderer.on('event', listener);
    return () => ipcRenderer.removeListener('event', listener);
  }
});
