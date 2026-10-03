# JyroCam Desktop (Windows)

Esta es la **aplicación de PC vigente** para la app Android JyroCam. Incluye servidor HTTP en puerto 8080, visor Electron, decodificador H.264 mediante FFmpeg, cámara virtual DirectShow y salida NDI opcional. La UI está en `viewer.html`; el servidor en `main.js`. El `server.js` de la raíz pertenece al prototipo anterior.

## Ejecutar y probar

```powershell
npm ci
npm test
npm start
npm run pack  # dist/win-unpacked/JyroCam.exe
npm run build # portable .exe en dist/
```

El portable actual se genera como `dist/JyroCam-Portable-1.0.2.exe` y el APK Android 1.0.2 desde `CamStreamApp/`. La cámara virtual se incluye como DLL empaquetada; el botón **Instalar cámara virtual** la registra para este usuario. El estado del botón distingue instalada, desactualizada, conectada y retirada. Se mantienen el ID del APK, el CLSID y la memoria compartida para actualizar instalaciones existentes.

Antes de iniciar otra copia, cierra la instancia anterior que escuche en el puerto 8080. Para conectarte por USB: `C:\Android\platform-tools\adb.exe reverse tcp:8080 tcp:8080`; usa `http://127.0.0.1:8080` en Android. Por Wi-Fi usa la IP del PC que muestra la ventana. Para instalar el dispositivo, pulsa **Instalar cámara virtual** y vuelve a abrir la aplicación que vaya a consumirla.

## Flujo y archivos

`POST /stream-h264` recibe NAL H.264 con prefijo de longitud de 4 bytes en big-endian. `main.js` mantiene una conexión activa, respeta backpressure y entrega NAL a `h264-decoder.js` (FFmpeg); `POST /upload-h264` sigue disponible para clientes anteriores. El visor Electron muestra solo el JPEG más reciente mientras termina de renderizar. `/status` expone métricas y `/frame.jpg` el último cuadro.

- `main.js`: recepción, vídeo, métricas, IPC, grabación, NDI y cámara virtual.
- `h264-decoder.js`: FFmpeg H.264 → BGRA 1280×720 y MJPEG nativo en pipes separados; no comprime JPEG ni convierte BMP en JavaScript. Usa decodificación por slices, timestamps continuos y contrapresión.
- `viewer.html`, `preload.js`, `styles.css`: interfaz y puente IPC.
- `virtual-cam-writer.js` y `virtual-cam/`: memoria compartida y filtro DirectShow; la DLL está en `virtual-cam/bin/JyroCamVirtualCam.dll` y registra el dispositivo como **JyroCam**.
- `test/stream-integration.js`: prueba de recepción persistente y decodificación JPEG.

Perfil vigente: captura y salidas **1280×720 a 30 FPS**, H.264 objetivo 6000 kbps y techo sostenido 8000 kbps en el enlace Android-PC. El filtro DirectShow entrega RGB24 1280×720; reconstruye `virtual-cam/build.bat` antes de empaquetar y pulsa **Actualizar cámara virtual** si hay un controlador anterior. Cierra/reabre la aplicación consumidora para cargar la nueva DLL. Se conservan CLSID y nombre de memoria compartida.

El visor distingue **kbps H.264** (caudal recibido) de **KB/cuadro** (tamaño del JPEG). NDI tiene su propio transporte: el límite de 8000 kbps corresponde al H.264 del teléfono, no al caudal del SDK NDI. Resultados medidos y pruebas reproducibles en [`PERFORMANCE.md`](PERFORMANCE.md).

Los binarios en `dist/` son locales e ignorados por Git: genera uno nuevo desde la fuente actual o usa un [release](https://github.com/jyrocchi/video-app/releases) asociado al tag correspondiente.

El release histórico v1.0.0, compilado desde `ba4a4be`, está en [v1.0.0-delay-v1](https://github.com/jyrocchi/video-app/releases/tag/v1.0.0-delay-v1). Los binarios `dist/` son artefactos ignorados: vuelve a generar el portable con `npm run build` desde la fuente actual y ejecuta `npm ci` tras limpiar `node_modules/`.
