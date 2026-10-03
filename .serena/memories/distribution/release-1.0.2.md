# JyroCam 1.0.2 — publicación verificada

GitHub release publicado y confirmado por API el2026-10-03: https://github.com/jyrocchi/video-app/releases/tag/v1.0.2 . Título exacto: Compatibilidad android + resolución fija. Es release público, no draft/no prerelease y Latest. Fuente del binario/tag: commit39751b044ef8eac1a468c883f56453503e7a063b en rama compatibilidad-android-resolucion-fija (nombre válido sin espacios). Main y releases anteriores conservados. Rama: https://github.com/jyrocchi/video-app/tree/compatibilidad-android-resolucion-fija .

Assets API-confirmados y SHA256 iguales a compilación local:
- JyroCam-Portable-1.0.2.exe:94093982bytes, fcd844211034ea20d7e05ea2f35fdbb52203d1386d3dfcdea4cfd1569b298d49.
- JyroCam-1.0.2.apk:10432933bytes,39215ef2124725731d634f81c7b8c8718e2a6016d6f2a0b256445fb9896f786d.
- checksums-1.0.2.sha256 adjunto.
Descargas /releases/download/v1.0.2/<asset>. Firma APK v2 verificada, IDcom.anomaly.camstream.debug/versionCode3/versionName1.0.2-debug (variante debug probada con misma clave de desarrollo). Electron1.0.2. IDs, CLSID y memoria compartida preservados. No guardar claves/tokens en conocimiento: solo hashes públicos.

Build/checks1.0.2: Android assembleDebug/AndroidTest/JVM/lint, npmci/npmtest/nativeDLL/npmrunbuild pasan. ASARversion1.0.2 y DLLempaquetada con mismo hash que fuente verificados. Binariosdist/apk ignorados; distribuidos como assets. RELEASE_NOTES_1.0.2.md y checksums-1.0.2.sha256 versionados. Memorias Serena y configuración de proyecto también en la rama; caché/logs/project.local excluidos. MCP Codebase video-app tiene ADR e índice persistente local actualizados; docs permiten reconstruir contexto tras clonar.

Perfil1280x720@30 fijo, Rotar oculto, sensor fijo. QualcommCBR6000/transporteH264máx8000, Wi-Fi/espejo30FPS, FFmpegBGRA/MJPEGnative, NDIbuffer/clockfix, DirectShowtemporizador1ms. CameraX1.6.2/AGP8.13.2/Kotlin2.2.21/Gradle8.13JDK17/compile36/min24/target34. Matriz Android7–17 funcional con ajuste gráfico delAVD17 explícito;14 físicoMotorola. EvidenciaAGENTS.md/CamStreamApp/COMPATIBILITY.md/CamStreamDesktop/PERFORMANCE.md y memorias streaming/720p30-profile, streaming/wifi-mirror-android-compatibility. No inferir latencia absoluta de las colas/FPS.