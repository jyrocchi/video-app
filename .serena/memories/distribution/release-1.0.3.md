# JyroCam 1.0.3 — publicación verificada 2026-10-04

Release pública, no prerelease, marcada Latest: https://github.com/jyrocchi/video-app/releases/tag/v1.0.3

Código y tag desde `9261e112250e4be3de08c5bdf0c0fd19ed1d59d4`, rama `compatibilidad-android-resolucion-fija`. Incluye perfiles nativos 720p30/720p60/1080p30, bitrate 6/10/12 Mbps y pacing 8/14/16 Mbps, selector compatible con cámara/codec, DirectShow tres formatos y grabación/NDI por perfil. Arquitectura y pruebas: `mem:streaming/selectable-profiles`, `CamStreamDesktop/PERFORMANCE.md`, `CamStreamApp/COMPATIBILITY.md`.

## Descargables confirmados por API de GitHub

- Windows x64: https://github.com/jyrocchi/video-app/releases/download/v1.0.3/JyroCam-Portable-1.0.3.exe — 94,097,435 bytes, SHA256 `B202EE3757A5E3EB5DDBD97126EBE72DDFDA5613934181FAB01A693CC9568CC8`.
- Android: https://github.com/jyrocchi/video-app/releases/download/v1.0.3/JyroCam-1.0.3.apk — 10,449,333 bytes, SHA256 `D1F3A228D9AA620BC3B2821B0CBE831870518F9FEA59BA8B3CCFC7C4F797E26C`.
- Integridad: https://github.com/jyrocchi/video-app/releases/download/v1.0.3/checksums-1.0.3.sha256 — hashes remotos coinciden con los locales.

Notas versionadas: `RELEASE_NOTES_1.0.3.md`; integridad: `checksums-1.0.3.sha256`. Las versiones anteriores 1.0.2, 1.0.1 y v1.0.0-delay-v1 se conservaron.

## Identidad, compilación y comprobación final

Electron 1.0.3; Android versionCode4/versionName1.0.3-debug, ID `com.anomaly.camstream.debug` conservado y firma APK v2 verificada con apksigner. Se conserva appId Electron, CLSID y nombre de memoria compartida. FinalAPK instalado en Motorola edge30fusion.

Gradle assembleDebug/assembleDebugAndroidTest/testDebugUnitTest/lintDebug PASS; npm test para tres perfiles/MP4 PASS; DLL C++ y portable recompilados. app.asar confirma versión1.0.3 y stream-profiles.js con los tres perfiles. Smoke de binarios1.0.3: WiFi1080p30 con espejo20.015s,30.027FPS decode/JPEG/render,11.960Mbps,cola0,reconexiones0;DirectShow300cuadros/300hashes30.017FPS. Validación extensa USB/WiFi y matriz56tests en documentos de rendimiento/compatibilidad.

`dist/` y `app/build/` ignorados; no adjuntar APK de instrumentación. Binarios finales son assets del release, no fuentes. Para próximas versiones actualizar package.json/package-lock y versionCode/versionName, construir ambos, generar hashes y confirmar assets con gh API. CLI gh puede carecer de sesión propia mientras Git tiene credenciales válidas; usar credencial de Git solo en memoria, nunca imprimir ni persistir tokens.

MCP Codebase `video-app`: ADR actualizado con publicación, hashes y mediciones; índice refrescado. Serena MCP presentó timeouts; esta memoria se guardó directamente en su carpeta persistente del proyecto para futuras sesiones.
