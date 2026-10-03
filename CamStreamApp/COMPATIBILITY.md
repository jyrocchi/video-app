# Compatibilidad Android — 3 de octubre de 2026

## Alcance del APK

`minSdk=24` (Android 7.0), `compileSdk=36`, `targetSdk=34`. CameraX **1.6.2**, AGP **8.13.2**, Kotlin **2.2.21**, Gradle **8.13** y JDK 17. El wrapper tiene SHA256 de distribución fijado. Se conservan ID y funciones de captura, transporte, espejo y servicio en primer plano. La ejecución en sistemas nuevos no equivale a adoptar todos los comportamientos de targetSdk 37.

Se corrigió una llamada sin protección a `Service.startForeground(id, notification, type)` (API 29). En API 24–28 se usa la variante de dos argumentos; desde API 29 se conserva el tipo cámara. Los canales se crean desde API 26 y los permisos de notificación se solicitan desde API 33. Los permisos de cámara, las notificaciones y las opciones Camera2 siguen pasando `lintDebug` sin errores de API.

La cámara frontal se deshabilita cuando CameraX no la ofrece; el servicio rechaza un cambio a una cámara inexistente antes de soltar la cámara activa. Esto corrigió la prueba de Android 8.1. Los errores de captura/servidor ahora aparecen en el resumen de Android.

## Matriz realmente ejecutada

Se instalaron las imágenes oficiales x86_64 del SDK, AEHD 2.2 y el emulador **37.3.2** (canal de pruebas). La matriz se repitió con las dependencias y correcciones finales. Cada fila de emulador aprobada ejecutó **tres pruebas instrumentadas**, no solo la instalación del APK:

1. Interfaz, Rotar oculto, promoción/notificación del servicio, captura y espejo cuando la cámara anuncia 720p, cambio de cámara disponible, parada del servicio. Se verifica el rechazo controlado si la cámara no ofrece la resolución requerida.
2. MediaCodec real a 1280×720 con cuadros YUV sintéticos, espejo durante parte del flujo, SPS/PPS y envío persistente. El receptor falla deliberadamente la primera consulta de capacidades: el emisor debe conservar las cabeceras y reintentar, sin tratar el fallo temporal como un escritorio antiguo.
3. Parada de MediaCodec mientras el consumidor de video mantiene un callback bloqueado más de 100 ms; al desbloquearse no debe acceder a buffers liberados ni provocar crash.

| Android | API | Resultado | Cámara de la imagen |
| --- | ---: | --- | --- |
| 7.0 | 24 | 3/3 PASS | No anuncia 720p; se verificaron rechazo controlado y pipeline sintético 720p |
| 7.1.1 | 25 | 3/3 PASS | Igual limitación de cámara emulada |
| 8.0 | 26 | 3/3 PASS | Captura 720p anunciada |
| 8.1 | 27 | 3/3 PASS | 720p trasero; selector frontal no disponible protegido |
| 9 | 28 | 3/3 PASS | Captura 720p anunciada |
| 10 | 29 | 3/3 PASS | Captura 720p anunciada |
| 11 | 30 | 3/3 PASS | Captura 720p anunciada |
| 12 | 31 | 3/3 PASS | Captura 720p anunciada |
| 12L | 32 | 3/3 PASS | Captura 720p anunciada |
| 13 | 33 | 3/3 PASS | Captura 720p anunciada |
| 14 | 34 | Dispositivo físico verificado | Motorola edge 30 fusion, captura nativa 720p30 y Wi-Fi con/sin espejo |
| 15 | 35 | 3/3 PASS | Captura 720p anunciada |
| 16 | 36 | 3/3 PASS | Captura 720p anunciada |
| 17, páginas de 4 KB | 37.0 | **3/3 PASS** | Captura 720p; ajuste gráfico del emulador descrito abajo |
| 17, páginas de 16 KB | 37.2 | **3/3 PASS** | Captura 720p; mismo ajuste gráfico del emulador |

### Resolución del bloqueo de Android 17

Las imágenes API 37.0/37.2 inicialmente reiniciaban SurfaceFlinger por una aserción de `mapper.ranchu.so` (`hasReadColorBufferDma`) y fallaba Package Manager antes de ejecutar el APK. La actualización del emulador por sí sola no lo arregló. Consultando el código AOSP se identificó la opción oficial `debug.sf.luma_sampling=0`: el harness puede aplicarla como root **solo al AVD de prueba** y reiniciar SurfaceFlinger. Desactiva el muestreo de luminancia de la interfaz virtual; no altera CameraX, MediaCodec, las API de servicio, el espejo ni el transporte de JyroCam. Con este ajuste ambas variantes de Android 17 pudieron instalar y ejecutar las pruebas completas.

Al poder probar el APK aparecieron dos fallos reales: CameraX 1.3.1 no convertía el perfil de rango dinámico `8192`, corregido al actualizar a 1.6.2; y la parada podía liberar MediaCodec mientras el hilo de salida aún usaba sus buffers. Ahora los accesos al codec se serializan, el buffer de salida se copia/libera antes de callbacks de red, y la parada impide accesos posteriores. Un fallo temporal al consultar `/status` tampoco activa prematuramente el envío de compatibilidad: se conserva la NAL y se reintenta la negociación.

La matriz verifica compatibilidad funcional bajo las condiciones descritas, incluyendo el ajuste gráfico de Android 17. No certifica todas las GPU, teléfonos o versiones de targetSdk. La captura nativa exige que el sensor ofrezca 1280×720, y los 30 FPS en hardware se midieron en el Motorola con Android 14.

## Reproducir las comprobaciones

Desde `CamStreamApp/`:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
node test-android-matrix.js 24 25 26 27 28 29 30 31 32 33 35 36
$env:CAMSTREAM_EMULATOR_DISABLE_LUMA = '1'
node test-android-matrix.js 37.0 37.2
```

El script requiere las imágenes instaladas, crea AVD propios en `C:\Users\PC\AppData\Local\Temp\opencode\jyrocam-avds`, usa solo `emulator-5560`, inicia un receptor de test aislado en 18081 y termina sus emuladores. No borra ni modifica el Motorola. Informes y JSON combinado: `C:\Users\PC\AppData\Local\Temp\opencode`. El informe registra versión de emulador, imagen, GPU, tamaño de página y si se aplicó el ajuste de luminancia.

`YuvFramePackerTest` tiene cinco pruebas JVM sobre buffers directos, padding, posiciones iniciales, última fila sin padding y formatos I420/NV12 con y sin espejo. Pasaron, junto con compilación, lint y la suite de escritorio.
