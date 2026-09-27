# CamStream Desktop (Windows)

Esta es la **aplicación de PC vigente** para la app Android `CamStreamApp/`. Incluye servidor HTTP en puerto 8080, visor Electron, decodificador H.264 mediante FFmpeg, cámara virtual DirectShow y salida NDI opcional. La UI está en `viewer.html`; el servidor en `main.js`. El `server.js` de la raíz pertenece al prototipo anterior.

## Ejecutar y probar

```powershell
npm ci
npm test
npm start
npm run pack  # dist/win-unpacked/CamStreamDesktop.exe
npm run build # portable .exe en dist/
```

Antes de iniciar otra copia, cierra la instancia anterior que escuche en el puerto 8080. Para conectarte por USB: `C:\Android\platform-tools\adb.exe reverse tcp:8080 tcp:8080`; usa `http://127.0.0.1:8080` en Android. Por Wi-Fi usa la IP del PC que muestra la ventana. Para instalar el dispositivo, pulsa **Instalar cámara virtual** y vuelve a abrir la aplicación que vaya a consumirla.

## Flujo y archivos

`POST /stream-h264` recibe NAL H.264 con prefijo de longitud de 4 bytes en big-endian. `main.js` mantiene una conexión activa, respeta backpressure y entrega NAL a `h264-decoder.js` (FFmpeg); `POST /upload-h264` sigue disponible para clientes anteriores. El visor Electron muestra solo el JPEG más reciente mientras termina de renderizar. `/status` expone métricas y `/frame.jpg` el último cuadro.

- `main.js`: recepción, vídeo, métricas, IPC, grabación, NDI y cámara virtual.
- `h264-decoder.js`: FFmpeg H.264 → BGRA; usa `low_delay` y un hilo.
- `viewer.html`, `preload.js`, `styles.css`: interfaz y puente IPC.
- `virtual-cam-writer.js` y `virtual-cam/`: memoria compartida y filtro DirectShow; la DLL está en `virtual-cam/bin/CamStreamVirtualCam.dll`.
- `test/stream-integration.js`: prueba de recepción persistente y decodificación JPEG.

La **captura Android** prioriza 1280×720; actualmente el decodificador y el filtro virtual entregan **640×480 RGB24** a los consumidores de la cámara virtual. Cambiar esa salida exige actualizar conjuntamente FFmpeg, la memoria compartida, la DLL y sus pruebas. Los binarios en `dist/` son locales e ignorados por Git: genera uno nuevo desde el commit actual o usa un [release](https://github.com/jyrocchi/video-app/releases) asociado al tag correspondiente.
