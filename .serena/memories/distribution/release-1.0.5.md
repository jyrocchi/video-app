# JyroCam 1.0.5 — publicación GitHub (2026-10-04)

Release publicada: https://github.com/jyrocchi/video-app/releases/tag/v1.0.5
Tag `v1.0.5` apunta al commit `e111e34` (`Release JyroCam 1.0.5: ajustes y rotación automática`) en `compatibilidad-android-resolucion-fija`. La rama y etiqueta fueron subidas a origin; revisión API confirmó release publicada, no borrador/pre-release.

## Assets confirmados por GitHub API
- Windows x64 `JyroCam-Portable-1.0.5.exe`: 94,114,053 bytes, SHA256 `37319679E81DA42F1F6412A3EE01405D213A3CCC18D7519C845EF525CF3201B3`; descarga https://github.com/jyrocchi/video-app/releases/download/v1.0.5/JyroCam-Portable-1.0.5.exe
- Android `JyroCam-1.0.5.apk`: 10,519,903 bytes, SHA256 `DAE43A1C563D5FEC408D8CC64ABE86CC96321C66ED58D51E0C1E68F763B9A713`; applicationId debug `com.anomaly.camstream.debug`, versionCode6/versionName1.0.5-debug; descarga https://github.com/jyrocchi/video-app/releases/download/v1.0.5/JyroCam-1.0.5.apk
- Checksums `checksums-1.0.5.sha256`, SHA256 del archivo `9AC37049CEDFF7A9C4716E0E2DE754D934431FFE13D29C4E1F1EEFE2E18786EE`; API digests de APK/portable coinciden con archivo checksums.

## Cambios y validación
- Viewer PC incluye brillo/contraste/saturación y blackout para cámara virtual y NDI; se corrigió ciclo de NDI runtime/sender. No hay cambio de protocolo H.264/perfiles.
- Android añade toggle de auto-rotación y optimiza transformación YUV rotada.
- `npm ci`, `npm test`, `npm run build` y `virtual-cam/build.bat` pasaron. `npm audit --omit=dev` 0 vulnerabilidades.
- Android `:app:testDebugUnitTest :app:assembleDebug` PASS; benchmark instrumentado en Motorola Edge 30 Fusion Android14: YUV sintético 720p con rotación90°; p50 referencia32.69ms, optimizado4.08ms/p95 4.54ms, speedup8.01×; no es FPS extremo a extremo.
- El informe completo `npm audit` observó 11 hallazgos en dependencias Electron/packaging (10 high, 1 critical); el arreglo automático propone upgrades mayores Electron44/electron-builder26, por eso no se aplicó como parte de 1.0.5.
- Release notes `RELEASE_NOTES_1.0.5.md`; hashes locales `checksums-1.0.5.sha256`.
