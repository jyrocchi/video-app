# JyroCam 1.0.2 — Compatibilidad android + resolución fija

Fuente: rama `compatibilidad-android-resolucion-fija` de `jyrocchi/video-app`.

## Descargas

- **Windows x64:** `JyroCam-Portable-1.0.2.exe`.
- **Android 7.0 o superior:** `JyroCam-1.0.2.apk`.

El APK publicado es la variante debug probada, firmada con la clave de desarrollo local y con ID `com.anomaly.camstream.debug`; su versión interna es `1.0.2-debug` y versionCode 3. Conserva el ID de las instalaciones previas de JyroCam debug. El EXE es portable e incluye FFmpeg y la DLL DirectShow JyroCam. NDI es opcional y requiere su runtime instalado.

## Novedades

- Perfil fijo **1280×720 a 30 FPS**, con Rotar oculto y deshabilitado y orientación independiente del giro físico del teléfono.
- H.264 CBR objetivo **6000 kbps** en el Motorola probado y pacing de transporte hasta **8000 kbps**.
- Espejo optimizado mediante empaquetado YUV por filas, con buffers reutilizados.
- Captura, visor, NDI y cámara virtual de PC en 1280×720; JPEG nativo en FFmpeg y cuadros BGRA por pipes separados.
- Recuperación de conexión, cabeceras SPS/PPS por keyframe y conservación de NAL cuando falla temporalmente la consulta de capacidades.
- MediaCodec protegido contra acceso concurrente/parada y liberación de buffers antes de callbacks de red.
- CameraX 1.6.2 y herramientas de compilación actualizadas; preservado Android 7 como mínimo.
- Cámara virtual DirectShow con temporización de alta precisión durante captura y publicación consistente de cuadros.

## Resultados comprobados

Motorola edge 30 fusion, Android 14, Wi-Fi de 5 GHz, 1280×720:

| Prueba | FPS medidos | Bitrate medio | Máximo muestreado |
| --- | ---: | ---: | ---: |
| Con espejo, 60 s | 30,01 | 5970 kbps | 6133 kbps |
| Sin espejo, 60 s | 30,00 | 5976 kbps | 6144 kbps |

DirectShow: 300 cuadros distintos, 1280×720, aproximadamente 29,93 FPS. Estos son resultados del entorno probado, no garantías para todo hardware o red ni una medición de latencia absoluta.

Compatibilidad funcional: tres pruebas instrumentadas por imagen en Android 7–13 y 15–17; Android 14 verificado en el Motorola. Android 17 (4 KB y 16 KB) se probó con un ajuste gráfico exclusivo del emulador para evitar un fallo de SurfaceFlinger. En las imágenes Android 7 sin cámara 720p se verificaron rechazo controlado y pipeline sintético de 720p. Detalles completos en `CamStreamApp/COMPATIBILITY.md` y `CamStreamDesktop/PERFORMANCE.md`.

## Actualización y conexión

1. Cierra la copia anterior de JyroCam en PC; una sola instancia usa el puerto 8080.
2. Abre el portable 1.0.2 e instala el APK actualizado.
3. Para Wi-Fi usa en Android la URL LAN que muestra el PC. Para USB: `adb reverse tcp:8080 tcp:8080` y `http://127.0.0.1:8080`.
4. Si usas cámara virtual, cierra Zoom/Discord u otro consumidor y pulsa **Actualizar cámara virtual** en JyroCam. Vuelve a abrir el consumidor.

## Construcción desde fuente

- Android/JDK 17, desde `CamStreamApp/`: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
- PC, desde `CamStreamDesktop/`: `npm ci`, `npm test`, `virtual-cam/build.bat`, `npm run build`.
- Los SHA256 de los dos descargables están en `checksums-1.0.2.sha256`, adjunto también al release.

## Integridad de los descargables

```text
fcd844211034ea20d7e05ea2f35fdbb52203d1386d3dfcdea4cfd1569b298d49  JyroCam-Portable-1.0.2.exe
39215ef2124725731d634f81c7b8c8718e2a6016d6f2a0b256445fb9896f786d  JyroCam-1.0.2.apk
```
