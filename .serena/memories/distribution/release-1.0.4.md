# JyroCam 1.0.4 "ajuste UI" — publicación 2026-10-04

Release candidata sobre commit `7154e81`, rama `compatibilidad-android-resolucion-fija`. Tag `v1.0.4` ya pusheado al remoto; queda pendiente la subida de binarios y notas a GitHub Releases.

## Cambios de UI

- Android (`CamStreamApp/app/src/main/res/values/strings.xml:3` + `activity_main.xml:140` + `MainActivity.kt:37-50, 109-114, 234-255, 261-263, 380-389`): el campo "URL del servidor" se renombra a **Ipv4 Local PC**, restringe la entrada a IPv4 (`digits="0123456789."` + `maxLength="15"`), valida con regex `(25[0-5]|2[0-4]\d|[01]?\d?\d)(\.(…)){3}` y construye `http://{ip}:8080` al iniciar. Persiste la IP en `server_ip` y migra el `server_url` legacy extrayendo la IPv4 embebida. El control de **Calidad** queda oculto (no se renderiza el spinner).
- Visor PC (`CamStreamDesktop/viewer.html:34-37, 114-115`): el placeholder muestra únicamente `info.ips[0]` (sin `http://` ni `:8080`), cambia el rótulo a "Abre la app JyroCam en tu Android y configura tu dirección IPv4:" y elimina las notas de `Puerto: 8080` y `Por USB: adb reverse`. El `<span id="portText">` queda oculto para no romper el `setInterval` de bitrate.

## Identidad y compilación

- Android versionCode 5 / versionName 1.0.4-debug, applicationId `com.anomaly.camstream.debug`, firma v2.
- Electron `jyrocam-desktop` 1.0.4 (paquete portable).
- `npm test` y `./gradlew.bat :app:assembleDebug` PASS; visor carga sin advertencias.
- Smoke Motorola USB (ID `ZY22G6BQKF`): `999.1.1.1` rechazado por regex IPv4 (mensaje "Ingresa una IPv4 válida (ej. 192.168.1.50)"); `192.168.1.50` arranca la transmisión con URL `http://192.168.1.50:8080`, overlay "EN VIVO" y botón "Detener".

## Descargables pendientes de publicar (binarios ya construidos)

- Windows x64: `JyroCam-Portable-1.0.4.exe` — 94,106,980 bytes, SHA256 `9F0656CCAE93D79789E7D29B554FD5DC0053BA95D784E413732145AB2E9AEE52`.
- Android: `JyroCam-1.0.4.apk` — 10,449,873 bytes, SHA256 `73AD0CBB28ABCA41E5D64486F4A8B9E0C9B33C0E4A385CD7AD57086ED7D99CB9`.
- Integridad: `checksums-1.0.4.sha256` con ambos hashes.

Notas versionadas en `RELEASE_NOTES_1.0.4.md`. Versiones anteriores 1.0.3, 1.0.2, 1.0.1 y v1.0.0-delay-v1 se mantienen en GitHub Releases.

## Procedimiento aplicado y recordatorios

- `dist/`, `app/build/`, `.gradle/` y `node_modules/` están en `.gitignore`; los binarios son assets del release, no fuentes. APK y portable se adjuntan a la publicación, nunca al repositorio.
- Antes de subir el siguiente binario, verificar que existe el reemplazo (no borrar la única compilación disponible).
- IDs, CLSID, nombre de memoria compartida, applicationId y appId Electron se conservan para evitar migración manual.
- Cambios puramente de UI: no se modificó el contrato del transporte (`POST /stream-h264` con `X-JyroCam-Profile` 720p30/720p60/1080p30), bitrates, pacing, ni las DLL DirectShow. Mediciones de 1.0.3 siguen aplicando; no se repitió captura absoluta de latencia.
- `gh` CLI no estaba autenticado en este shell mientras `git` sí; el tag se subió vía `git push` y los assets se publican con `gh release create` tras autenticar. No imprimir ni persistir tokens; usar la credencial de Git solo en memoria.

## MCP

- Codebase `video-app`: ADR reemplazado para incluir la sección "Publicación 1.0.4 'ajuste UI' (2026-10-04)" con hashes, commit y smoke; índice reindexado en modo `moderate`.
- Serena: esta memoria escrita directamente en `.serena/memories/distribution/release-1.0.4.md` porque `list_memories` presentó timeout en la sesión actual; mantiene el patrón usado para 1.0.3.