# CamStream: celular Android → PC Windows

**Aplicación vigente:** `CamStreamApp/` (Android) transmite H.264 a `CamStreamDesktop/` (Electron). La aplicación de PC recibe el vídeo en el puerto 8080, lo muestra y puede enviarlo por NDI o a la cámara virtual DirectShow. Si vas a modificar o ejecutar el proyecto, comienza por esas dos carpetas y consulta `AGENTS.md`.

`server.js`, `phone.html` y `viewer.html` en la raíz son un prototipo anterior basado en el navegador; no son el servidor ni el visor usados por la app Android vigente. No ejecutes `server.js` junto con CamStream Desktop: ambos utilizan el puerto 8080.

## Versión verificada

El [release **v1.0.0-delay-v1**](https://github.com/jyrocchi/video-app/releases/tag/v1.0.0-delay-v1) contiene `CamStreamDesktop-Portable-1.0.0.exe` (Windows x64) y `app-debug.apk` (Android; ID `com.anomaly.camstream.debug`). Ambos se construyeron y probaron desde el commit `ba4a4be` de las aplicaciones vigentes. La fuente más reciente para editar está en `main`; antes de considerar un binario de `dist/` como equivalente, comprueba el commit/tag desde el que se generó.

## Uso

1. En Windows abre **CamStream Desktop** (el ejecutable del release vigente o `npm start` desde `CamStreamDesktop/`). Cierra otras instancias que ocupen el puerto 8080.
2. En el teléfono abre **CamStream**. Por Wi-Fi usa la URL `http://<IP-del-PC>:8080` que muestra la ventana. Por USB, ejecuta `C:\Android\platform-tools\adb.exe reverse tcp:8080 tcp:8080` y utiliza `http://127.0.0.1:8080` en el teléfono.
3. Selecciona **Alta (85)** y **20 FPS** (valores iniciales), e inicia la transmisión. La captura prioriza 1280×720; el resultado real aparece en el registro de la app.

La cámara virtual DirectShow instalada desde la ventana de escritorio utiliza actualmente una salida fija de 640×480. Es distinta de la resolución de captura Android; consulta `CamStreamDesktop/README.md` antes de cambiarla.

## Desarrollar y generar aplicaciones

Requisitos: Node.js y npm, JDK 17, Android SDK 34 y Gradle 8.5/Android Studio. En este equipo Gradle está en `C:\Android\gradle-8.5\bin\gradle.bat` y el SDK en `C:\Android`.

```powershell
# Desde CamStreamDesktop/
npm ci
npm test
npm start
npm run pack     # dist/win-unpacked/CamStreamDesktop.exe
npm run build    # ejecutable portable en dist/

# Desde CamStreamApp/
& "C:\Android\gradle-8.5\bin\gradle.bat" :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

`dist/`, `app/build/` y los APK ignorados por Git son **artefactos locales**, no la fuente de verdad. Un ejecutable antiguo en `dist/` puede tener código distinto del último commit: recompila o descarga los binarios asociados al tag del [release](https://github.com/jyrocchi/video-app/releases) antes de probar cambios.

## Dónde modificar

- `CamStreamApp/app/src/main/java/com/anomaly/camstream/StreamService.kt`: captura CameraX, resolución y FPS.
- `H264Encoder.kt` y `FrameUploader.kt` en ese mismo directorio: codificación y transmisión H.264 persistente (`POST /stream-h264`).
- `CamStreamDesktop/main.js` y `h264-decoder.js`: servidor, FFmpeg, salidas de vídeo y métricas.
- `CamStreamDesktop/viewer.html`, `preload.js`, `styles.css`: interfaz Electron e IPC.
- `CamStreamDesktop/virtual-cam/` y `virtual-cam-writer.js`: filtro DirectShow y memoria compartida.

La referencia de diagnóstico y decisiones de latencia está en el ADR del proyecto `video-app` del MCP. Sigue el código y los commits como fuente de verdad para la implementación.
