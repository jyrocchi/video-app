# JyroCam 1.0.3 — Perfiles HD y Full HD

## Descargas

- Windows x64: `JyroCam-Portable-1.0.3.exe`.
- Android 7.0 o posterior: `JyroCam-1.0.3.apk` (ID `com.anomaly.camstream.debug`, actualización de los APK anteriores).
- Integridad SHA256: `checksums-1.0.3.sha256`.

## Cambios

- Selector de 1280×720 a 30 FPS, 1280×720 a 60 FPS y 1920×1080 a 30 FPS.
- Perfiles nativos filtrados por capacidades reales de la cámara y del codificador Android; se omiten las combinaciones incompatibles.
- H.264 objetivo 6/10/12 Mbps y margen de transporte 8/14/16 Mbps; niveles AVC Baseline 3.1/3.2/4.0, prioridad al codificador hardware y CBR cuando está disponible.
- Negociación de perfil entre Android y PC, reinicio de FFmpeg por sesión y protección frente a callbacks antiguos.
- Visor, salida NDI y grabación MP4 conservan resolución y FPS del perfil elegido.
- Cámara virtual DirectShow con tres formatos RGB24, memoria ampliada para Full HD, publicación segura y cadencia guiada por cuadros nuevos.
- Espejo optimizado mediante copia YUV por filas y buffers reutilizables; no se baja resolución para mantener FPS.
- Compatible con el protocolo anterior 720p30; las nuevas opciones requieren actualizar ambos extremos.

## Validación

- Motorola edge 30 fusion / Android 14, cámara trasera: los tres perfiles. Frontal: 720p30 y 1080p30; 60 FPS no se ofrece.
- Medición real de aproximadamente 60 segundos por perfil y enlace: USB 30,008 / 60,023 / 30,006 FPS; Wi-Fi 30,013 / 60,019 / 29,994 FPS. Wi-Fi también probado con espejo a 60 FPS y Full HD.
- Sin reconexiones ni nuevos errores de salida durante las ventanas medidas. DirectShow comprobado con 300 cuadros distintos por perfil, alrededor de 30/60 FPS.
- Suite de escritorio: los tres perfiles BGRA/JPEG, parsers, color/memoria compartida y MP4 decodificable con dimensiones/FPS correctos.
- Android: compilación, pruebas JVM y lint; 56 pruebas instrumentadas aprobadas en API 24–33, 35, 36 y Android 17 de 4/16 KB. API 34 comprobada en dispositivo físico.
- Los codecs sintéticos del emulador verifican compatibilidad de las API, no garantizan captura a 60 FPS en cada modelo. Android 17 se comprobó con el ajuste gráfico de AVD documentado en `CamStreamApp/COMPATIBILITY.md`.

## Uso y actualización

1. Actualiza el APK y el portable de PC.
2. Cierra consumidores de cámara virtual, pulsa **Actualizar cámara virtual** y vuelve a abrirlos.
3. Elige **Resolución / FPS** antes de iniciar. Detén la transmisión para cambiar perfil.
4. DirectShow: selecciona el mismo formato en OBS u otro consumidor; reabre la captura al cambiar resolución.
5. Wi-Fi: usa `http://<IP-del-PC>:8080`. USB: configura `adb reverse tcp:8080 tcp:8080` y usa `http://127.0.0.1:8080`.

Mediciones completas: `CamStreamDesktop/PERFORMANCE.md`. Compatibilidad: `CamStreamApp/COMPATIBILITY.md`. No se midió latencia absoluta cámara-pantalla ni autonomía térmica de varias horas.

Se conservan IDs, CLSID y nombre de memoria compartida. Las versiones anteriores permanecen en GitHub Releases.
