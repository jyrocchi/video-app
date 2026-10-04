# JyroCam 1.0.5 — ajustes de imagen y rotación automática

## Descargas

- Windows x64: `JyroCam-Portable-1.0.5.exe`.
- Android 7.0 o posterior: `JyroCam-1.0.5.apk` (ID `com.anomaly.camstream.debug`, versionCode 6, versionName `1.0.5-debug`).
- Integridad SHA256: `checksums-1.0.5.sha256`.

## Cambios

- **PC:** controles de brillo, contraste y saturación (0–200 %, 100 % neutro), persistentes y restablecibles. Se aplican al visor y a las salidas de cámara virtual y NDI; la grabación conserva el cuadro original.
- **PC/NDI:** detección del runtime NDI, estado de disponibilidad recuperable y posibilidad de reintentar o recrear el sender sin reiniciar JyroCam.
- **PC:** el control «Oscurecer vista» también envía cuadros negros a la cámara virtual y NDI.
- **Android:** opción persistente `Rot. auto` para orientar la imagen con el sensor durante la transmisión.
- **Android:** transformación YUV de cuadros rotados optimizada con lectura por filas y buffers reutilizables; se mantienen resolución, perfil, bitrate y calidad.

## Compatibilidad

- Se conserva el protocolo `POST /stream-h264` y la cabecera `X-JyroCam-Profile`; perfiles 720p30, 720p60 y 1080p30.
- Se mantienen los IDs de aplicación Android/Electron, CLSID DirectShow y nombre de memoria compartida.
- Se recomienda actualizar ambos extremos a 1.0.5.

## Validación

- Escritorio: `npm test` pasó para filtros/blackout, ciclo de NDI, decodificación y perfiles 720p30, 720p60 y 1080p30. La DLL de cámara virtual se recompiló antes de empaquetar.
- Android: `:app:testDebugUnitTest` y `:app:assembleDebug` pasaron; benchmark instrumentado en Motorola edge 30 fusion, Android 14.
- En el benchmark de transformación YUV sintética directa 1280×720 rotada 90°, la mediana bajó de 32,69 ms a 4,08 ms por cuadro (8,01×); p95 4,54 ms. Es una medición de la transformación en el dispositivo, no FPS extremo a extremo.

## Actualización

1. Instala JyroCam 1.0.5 para Windows y Android.
2. En Android, activa **Rot. auto** para que el sensor oriente los cuadros durante la transmisión.
3. Configura la IPv4 local del PC y elige el perfil antes de iniciar.
4. Para NDI, instala NDI Runtime o NDI Tools x64 si JyroCam informa que falta el runtime; vuelve a activar NDI después de instalarlo.
