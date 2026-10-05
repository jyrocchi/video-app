# JyroCam 1.0.5 — ajustes de imagen y rotación automática

## Descargas

- Windows x64: `JyroCam-Portable-1.0.5.exe`.
- Android 7.0 o posterior: `JyroCam-1.0.5.apk` (ID `com.anomaly.camstream.debug`, versionCode 6, versionName `1.0.5-debug`).
- Integridad SHA256: `checksums-1.0.5.sha256`.

## Cambios

### Corrección de cámara virtual — 5 de octubre de 2026

- Se actualizó el ejecutable Windows de este release conservando la versión **1.0.5**. Descárgalo nuevamente si no podías instalar la cámara virtual.
- Se reconstruyó la DLL desde el código fuente después de confirmar que Defender había retirado la anterior del paquete y de la instalación (`Trojan:Win32/Bearfoos.A!ml`). El nuevo binario pasó el análisis local de Defender sin nuevas detecciones.
- El botón **Instalar cámara virtual** comprueba el registro efectivo en Windows y muestra un error útil si falta la DLL, se bloquea su lectura o falla el registro. Se corrigió la propagación de errores del registro COM y la liberación de la DLL después de instalar.
- El empaquetado recompila automáticamente el filtro DirectShow. Validación: `npm test`, registro COM, enumeración DirectShow y captura de cinco cuadros 720p30 con FFmpeg.
- Cierra la aplicación anterior, abre el portable actualizado, pulsa **Instalar/Actualizar cámara virtual** y vuelve a abrir la aplicación que vaya a usar **JyroCam**.
- El APK Android conserva su contenido y SHA256. El archivo de hashes incluye el nuevo ejecutable Windows.
- El tag `v1.0.5` conserva la publicación original; el código de la corrección se publica en la rama [`compatibilidad-android-resolucion-fija`](https://github.com/jyrocchi/video-app/tree/compatibilidad-android-resolucion-fija).

### Funciones de 1.0.5

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
