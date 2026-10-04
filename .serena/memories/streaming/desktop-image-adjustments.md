# JyroCam 1.0.5 — salidas de imagen, NDI y rotación automática

Cambios locales posteriores al release publicado 1.0.4. La versión candidata local es Electron 1.0.5 y Android versionCode 6/versionName 1.0.5-debug; no se publicó GitHub Release.

## PC: ajustes de imagen y blackout

`CamStreamDesktop/viewer.html` presenta **Ajustes de imagen** antes de **Oscurecer vista**. Brillo, contraste y saturación usan sliders 0–200%, neutro 100%, persistencia localStorage y reset. `#frame` conserva filtros CSS para vista local. `CamStreamDesktop/main.js` usa `ImageAdjustments.apply` sobre los cuadros BGRA antes de pasarlos a `NdiSender.sendBgra` y `VirtualCamWriter.writeBgra`; blackout entrega BGRA negro opaco. Grabación sigue recibiendo los JPEG originales; el protocolo/stream móvil no cambia.

El algoritmo BGRA replica el orden brightness→contrast→saturate con LUT para brillo/contraste y saturación RGB; buffer reutilizado. En 720p un benchmark local midió 5,1 ms/cuadro para valores no neutros. Los filtros CSS son estándar: brillo 0% negro; contraste 0% gris; saturación 0% escala de grises; cada 100% neutro. `saturate()` opera sobre RGB, no HSL.

## PC: NDI

Repro anterior: tras desactivar NDI, `ndiInitError` no se borraba y UI decía "NDI no disponible"; recrear sender producía `Duplicate type name 'NDIlib_send_create_t'`. Se aclara la disponibilidad consultando/cargando el runtime desde rutas NDI instaladas/env y reintentando si falta; UI diferencia no disponible/desactivado/activo/error reintentable y deja el toggle desactivado hasta que el runtime esté disponible. `loadRuntime()` inicializa el SDK una vez y los structs Koffi son anónimos para permitir ciclos. Prueba en este PC: runtime detectado, crear/cerrar sender tres veces PASS. `CamStreamDesktop/test/ndi-retry.js` cubre ciclos cuando NDI está instalado y fallback ausente.

## Android: rotación de orientación

El antiguo botón de rotación oculto ahora conmuta `Rot. auto: Sí/No`, guarda `auto_rotate` y notifica `StreamService` incluso durante transmisión. `OrientationEventListener` ajusta `ImageAnalysis.targetRotation` con histéresis; `image.imageInfo.rotationDegrees` se pasa a H264Encoder para transformar YUV antes de codificar a los perfiles de salida fijos. El modo fijo por defecto conserva el comportamiento anterior. Pruebas JVM verifican mapeo de las cuatro orientaciones Surface, lecturas desconocidas e histéresis.

## Validación y binarios locales

- Desktop `npm test`: cámara virtual/color, filtros y blackout, NDI close/reopen x3, parser/frame/MP4 en 720p30/720p60/1080p30 PASS.
- Android `:app:testDebugUnitTest :app:assembleDebug` PASS; APK versionCode6 instalada en Motorola `ZY22G6BQKF`; botón `Rot. auto: Sí` cambia y conserva valor al reiniciar. El giro físico no se pudo automatizar con ADB; el mapeo sensor→Surface está cubierto por tests JVM.
- Portable: `CamStreamDesktop/dist/JyroCam-Portable-1.0.5.exe`, 94,114,053 bytes, SHA256 `37319679E81DA42F1F6412A3EE01405D213A3CCC18D7519C845EF525CF3201B3` (portable final 1.0.5).
- APK: `CamStreamApp/app/build/outputs/apk/debug/app-debug.apk`, 10,519,903 bytes, SHA256 `DAE43A1C563D5FEC408D8CC64ABE86CC96321C66ED58D51E0C1E68F763B9A713` (APK 1.0.5 final).

Referencias CSS: https://developer.mozilla.org/en-US/docs/Web/CSS/filter-function/brightness, https://developer.mozilla.org/en-US/docs/Web/CSS/filter-function/contrast, https://developer.mozilla.org/en-US/docs/Web/CSS/filter-function/saturate.

Serena MCP presentó timeout; memoria guardada directamente aquí. Codebase graph se refresca por separado.
