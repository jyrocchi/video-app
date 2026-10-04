# JyroCam: celular Android → PC Windows

**Aplicación vigente:** `CamStreamApp/` (Android, nombre visible JyroCam) transmite H.264 a `CamStreamDesktop/` (Electron, nombre visible JyroCam). La aplicación de PC recibe el vídeo en el puerto 8080, lo muestra y puede enviarlo por NDI o a la cámara virtual DirectShow **JyroCam**. Si vas a modificar o ejecutar el proyecto, comienza por esas dos carpetas y consulta `AGENTS.md`.

`server.js`, `phone.html` y `viewer.html` en la raíz son un prototipo anterior basado en el navegador; no son el servidor ni el visor usados por la app Android vigente. No ejecutes `server.js` junto con CamStream Desktop: ambos utilizan el puerto 8080.

## Versión verificada

**JyroCam 1.0.3 — Perfiles HD y Full HD**:

- [Release y notas](https://github.com/jyrocchi/video-app/releases/tag/v1.0.3)
- [Descargar Windows x64](https://github.com/jyrocchi/video-app/releases/download/v1.0.3/JyroCam-Portable-1.0.3.exe)
- [Descargar APK Android](https://github.com/jyrocchi/video-app/releases/download/v1.0.3/JyroCam-1.0.3.apk)
- [Checksums SHA256](https://github.com/jyrocchi/video-app/releases/download/v1.0.3/checksums-1.0.3.sha256)

### Versión anterior

[JyroCam 1.0.2 — Compatibilidad android + resolución fija](https://github.com/jyrocchi/video-app/releases/tag/v1.0.2) incluye el portable Windows y el APK actualizado (`com.anomaly.camstream.debug`). Fuente en la rama [`compatibilidad-android-resolucion-fija`](https://github.com/jyrocchi/video-app/tree/compatibilidad-android-resolucion-fija); código del binario/tag desde `39751b0`. Se conservan los releases [1.0.1](https://github.com/jyrocchi/video-app/releases/tag/v1.0.1) y [v1.0.0-delay-v1](https://github.com/jyrocchi/video-app/releases/tag/v1.0.0-delay-v1).

- [Descargar Windows x64](https://github.com/jyrocchi/video-app/releases/download/v1.0.2/JyroCam-Portable-1.0.2.exe)
- [Descargar APK Android](https://github.com/jyrocchi/video-app/releases/download/v1.0.2/JyroCam-1.0.2.apk)
- [SHA256 de los archivos](https://github.com/jyrocchi/video-app/releases/download/v1.0.2/checksums-1.0.2.sha256)

## Uso

La versión 1.0.3 incorpora los perfiles seleccionables. Actualiza ambos extremos; el release 1.0.2 anterior conserva la resolución fija.

1. En Windows abre **JyroCam** (el portable rebrandeado o `npm start` desde `CamStreamDesktop/`). Cierra otras instancias que ocupen el puerto 8080.
2. En el teléfono abre **JyroCam**. Por Wi-Fi usa la URL `http://<IP-del-PC>:8080` que muestra la ventana. Por USB, ejecuta `C:\Android\platform-tools\adb.exe reverse tcp:8080 tcp:8080` y utiliza `http://127.0.0.1:8080` en el teléfono.
3. Antes de transmitir, elige **1280×720 / 30 FPS**, **1280×720 / 60 FPS** o **1920×1080 / 30 FPS**. Solo aparecen perfiles compatibles con la cámara y el codificador del teléfono. Los bitrates son 6/10/12 Mbps, con margen de transporte de 8/14/16 Mbps respectivamente. Detén la transmisión para cambiar de perfil. **Rotar** permanece oculto; espejo disponible en los tres perfiles.

El visor, NDI y las grabaciones siguen el perfil elegido. DirectShow ofrece los tres formatos; selecciona el mismo perfil en OBS u otro consumidor. Tras actualizar, cierra consumidores, pulsa **Actualizar cámara virtual** y vuelve a abrirlos. Reabre la captura tras cambiar resolución. Compatibilidad Android 7–17 y mediciones USB/Wi-Fi/espejo: `CamStreamApp/COMPATIBILITY.md` y `CamStreamDesktop/PERFORMANCE.md`.

## Desarrollar y generar aplicaciones

Requisitos: Node.js/npm, JDK 17 y Android SDK 36. Se incluye el wrapper Gradle 8.13 con checksum fijado; Android usa AGP 8.13.2, Kotlin 2.2.21 y CameraX 1.6.2. El APK conserva minSdk 24 y targetSdk 34.

```powershell
# Desde CamStreamDesktop/
npm ci
npm test
npm start
virtual-cam/build.bat
npm run pack     # dist/win-unpacked/JyroCam.exe
npm run build    # ejecutable portable en dist/

# Desde CamStreamApp/
.\gradlew.bat :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

`dist/`, `app/build/` y los APK ignorados por Git son **artefactos locales**, no la fuente de verdad. Un ejecutable antiguo en `dist/` puede tener código distinto del último commit: recompila o descarga los binarios asociados al tag del [release](https://github.com/jyrocchi/video-app/releases) antes de probar cambios.

## Dónde modificar

- `CamStreamApp/app/src/main/java/com/anomaly/camstream/StreamService.kt`: captura CameraX, resolución y FPS.
- `H264Encoder.kt` y `FrameUploader.kt` en ese mismo directorio: codificación y transmisión H.264 persistente (`POST /stream-h264`).
- `CamStreamDesktop/main.js` y `h264-decoder.js`: servidor, FFmpeg, salidas de vídeo y métricas.
- `CamStreamDesktop/viewer.html`, `preload.js`, `styles.css`: interfaz Electron e IPC.
- `CamStreamDesktop/virtual-cam/` y `virtual-cam-writer.js`: filtro DirectShow y memoria compartida.

El ADR y el índice persistente de Codebase Memory (`video-app`) conservan diagnóstico/mediciones; Serena tiene memorias reutilizables en `.serena/memories/`, versionadas junto con el proyecto. `RELEASE_NOTES_1.0.2.md` registra la publicación y `checksums-1.0.2.sha256` la integridad de los descargables. Sigue el código y los commits como fuente de verdad para la implementación.
