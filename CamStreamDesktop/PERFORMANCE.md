# Perfil 720p30 — verificación del 3 de octubre de 2026

**Registro histórico.** La implementación vigente y sus resultados USB/Wi-Fi están en la sección [Perfiles seleccionables](#perfiles-seleccionables--validación-local-2026-10-03) al final de este documento.

## Configuración

- Motorola edge 30 fusion (`tundra`) conectado por USB/ADB reverse.
- CameraX: 1280×720 YUV, AE `[30,30]`, orientación fija, sin giro automático/manual.
- MediaCodec `c2.qti.avc.encoder`: 1280×720@30, CBR 6000 kbps; GOP de un segundo.
- POST H.264 persistente y ordenado; pacing máximo de 8000 kbps para el payload comprimido con prefijos de longitud. HTTP/TCP y NDI tienen su propio overhead/transporte.
- FFmpeg: una decodificación, salida BGRA fija 720p y MJPEG nativo para el visor/grabador. Timestamps de salida continuos y sin reconstruir BMP en JS.
- NDI: reloj de cámara, doble buffer para no sobrescribir un envío asíncrono en curso.
- DirectShow: RGB24 1280×720, muestras con intervalo racional 1/30 s. No emite la misma marca de cuadro dos veces; comprueba que el productor no cambió los píxeles durante su copia. Solo entrega cuando el grafo está ejecutándose.

## Resultado real del binario empaquetado

Medición de **60,041 segundos**, muestreo cada dos segundos, NDI activo y visor funcionando incluso ocluido. Se ejecutó una captura DirectShow simultánea durante diez segundos.

| Magnitud | Resultado |
| --- | ---: |
| Resolución capturada/codificada del Motorola | 1280×720 |
| Resolución JPEG recibida por HTTP y mostrada por Electron | 1280×720 |
| Cuadros decodificados / JPEG / ACK del visor | 1802 / 1802 / 1802 |
| FPS de cada una de las tres etapas | 30,013 |
| Bitrate H.264 medio | 6000,72 kbps |
| Rango de bitrate en muestras de 2 s | 5954–6094 kbps |
| Cola de entrada FFmpeg en las muestras | 0 bytes |
| Nuevas conexiones en la ventana | 0 |
| Errores de FFmpeg, envío NDI y publicación de cámara virtual | 0 |
| Callback PC (NDI + conversión/publicación RGB24) | 2,3–2,8 ms |
| Cámara virtual: 300 muestras RGB24 / hashes distintos | 300 / 300 |
| Cadencia real de esas 300 muestras | 29,897 FPS |

Logs Android finales: captura trasera 30,0 FPS, `gateSkip=0`, cola 0/12, copia YUV ~1,7 ms y `drop=0` en régimen estable. Los contadores acumulados de starvation de entrada (19) y reconexiones (33, tras reinicios intencionales durante desarrollo) permanecieron sin aumentar durante la medición final. En el primer ensayo limpio ambos eran cero. La captura nativa exacta y CBR fueron confirmados por el propio dispositivo.

Como referencia anterior, el callback de PC era ~27,5 ms (JPEG JS ~14,4 ms y reloj NDI ~11,5 ms); la copia UV con lecturas individuales de ByteBuffer tardaba ~13,7 ms. Son mediciones de etapas, no porcentajes de CPU ni latencia absoluta captura-pantalla.

Se validó además una grabación MP4 real de 105,23 s: 1280×720, 30 FPS, 3157 cuadros, ~5717 kbps de video, legible íntegramente con FFmpeg. La grabación vuelve a comprimir el JPEG; las salidas NDI/DirectShow consumen el cuadro decodificado directamente.

## Pruebas reproducibles

Desde `CamStreamDesktop/`:

```powershell
npm test
virtual-cam/build.bat
npm run build
node test/live-performance.js 60
node test/virtualcam-live.js
```

`live-performance.js` requiere Android transmitiendo al PC. Cuenta diferencias de cuadros reales, bytes recibidos y ACKs del visor; no toma el FPS configurado como prueba. `virtualcam-live.js` requiere la DLL actual registrada como JyroCam y abre un grafo DirectShow real, verifica dimensiones, cadencia y hashes de 300 cuadros.

`npm test` comprueba integridad de color/publicación en memoria, límites de cuadros fragmentados entre chunks, 60/60 cuadros BGRA y JPEG de FFmpeg, recepción persistente, visor y MP4.

Se probó reiniciar el receptor sin reiniciar Android: la transmisión se recupera al recibir los SPS/PPS repetidos y el siguiente IDR. El decodificador ignora slices previos a la configuración, evita PTS duplicados y cierra el POST si FFmpeg falla para no dejar una conexión bloqueada.

Estos resultados corresponden a este teléfono/PC y USB. Los tiempos de etapas y las colas vacías no equivalen a una medición de latencia absoluta; no se ha medido Wi-Fi ni Discord/Zoom. Un enlace insuficiente puede causar contrapresión: no se promete eliminar pérdidas físicas ni mantener 30 cuadros nuevos por segundo sin capacidad de transporte.

## Referencias consultadas

- [Ayuda oficial de DroidCam](https://www.droidcam.app/help/): recomienda 1280×720/30 FPS y USB para estabilidad; advierte sobre interferencia y límites de cámara de Android.
- [DroidCam OBS](https://github.com/dev47apps/droidcam-obs-plugin): arquitectura de decodificación nativa y aceleración, usada como referencia de diseño; no se incorporó su código.
- [Documentación FFmpeg](https://ffmpeg.org/ffmpeg-doc.html): salidas múltiples, mapeo, filtro `split` y modos de sincronización.
- [Koffi — punteros](https://koffi.dev/pointers): aritmética de direcciones y lifetime de buffers nativos. Koffi 2.x devuelve External; se usa `koffi.address` al publicar la memoria compartida.

## Wi-Fi y espejo — medición posterior de la misma sesión

Motorola con Android 14, Wi-Fi 6 (802.11ax), banda de 5 GHz/5500 MHz, RSSI aproximadamente −31 a −36 dBm y enlace negociado 1200 Mbps. PC en Ethernet de la misma LAN. Se cambió la URL de Android a la IP LAN del PC y se retiró `adb reverse` para 8080; `/status.h264.peerAddress` confirmó la dirección Wi-Fi del teléfono. El cable quedó únicamente para depuración/carga.

| Escenario | Duración | FPS decode/JPEG/render ACK | H.264 medio | Rango en muestras de 2 s |
| --- | ---: | ---: | ---: | ---: |
| Antes: espejo encendido, copia por píxel | 20,014 s | 17,088 | 3428,36 kbps | 3306–3510 kbps |
| Después: Wi-Fi, espejo apagado | 60,041 s | 29,996 | 5998,51 kbps | 5840–6137 kbps |
| Después: Wi-Fi, espejo encendido | 60,037 s | 30,015 | 5996,81 kbps | 5869–6153 kbps |
| Wi-Fi, espejo encendido y teléfono con pantalla apagada/Dozing | 30,021 s | 30,012 | 5991,28 kbps | 5808–6116 kbps |

En las tres mediciones finales no hubo nuevas conexiones, error del decodificador ni nuevos errores de salida NDI/cámara virtual. Cola FFmpeg muestreada: 0 bytes. Callback PC alrededor de 2,4–2,9 ms. Se mantuvieron dimensiones exactas 1280×720 y el perfil CBR 6000 kbps/techo 8000 kbps.

El problema de espejo estaba en el camino genérico: por cada píxel se hacía lectura individual de ByteBuffer y cálculo de coordenadas. Ahora `YuvFramePacker` lee filas completas por JNI, invierte los arrays y conserva pares U/V. Sus arrays se reutilizan. El caso de tamaño fijo, con y sin espejo, comparte el camino rápido.

Android mantiene un wake lock CPU renovable durante la transmisión y, al usar Wi-Fi, un lock de baja latencia desde API 29 o alto rendimiento en API 24–28. Se liberan al parar el servicio. En PC se explicitan TCP_NODELAY y keepalive, y se expone el peer del POST para comprobar el transporte utilizado.

Las colas vacías, los FPS y los tiempos de etapas no son una medición absoluta de latencia de cámara a pantalla. La prueba Dozing es corta y no demuestra autonomía térmica de varias horas ni todas las condiciones de ahorro de batería. Compatibilidad de versiones y condiciones de prueba de Android 17: [`CamStreamApp/COMPATIBILITY.md`](../CamStreamApp/COMPATIBILITY.md).

## Continuación: Android 17 y nueva validación Wi-Fi

El bloqueo del emulador se superó con su ajuste gráfico `debug.sf.luma_sampling=0`, aplicado por el harness únicamente al AVD Android 17. Las pruebas reales descubrieron el perfil de rango dinámico `8192` desconocido en CameraX 1.3.1 y una carrera de liberación de MediaCodec. Se actualizaron CameraX a 1.6.2 y las herramientas de compilación, se serializaron los accesos/parada del codec y se liberan sus buffers antes de callbacks que puedan bloquearse. Un fallo temporal de la consulta de capacidades conserva la NAL y vuelve a consultar, sin activar HTTP de compatibilidad prematuramente.

Tres pruebas instrumentadas por imagen, repetidas sobre API 24–33, 35–36 y Android 17 API 37.0/37.2 (4 KB y 16 KB), pasan. El APK final se instaló en el Motorola Android 14; el portable y la DLL actualizados se instalaron y abrieron en PC.

| Escenario con el APK actualizado | Duración | FPS decode/JPEG/render ACK | H.264 medio | Máximo observado por muestra |
| --- | ---: | ---: | ---: | ---: |
| Wi-Fi, espejo encendido | 60,046 s | 30,010 | 5970,43 kbps | 6133 kbps |
| Wi-Fi, espejo apagado | 60,033 s | 30,000 | 5975,54 kbps | 6144 kbps |

Resolución exacta 1280×720; cola FFmpeg 0 bytes en las muestras; sin nuevas conexiones ni errores de salida. Logs del Motorola: sensor/captura 30 FPS, `inputStarve=0`, `drop=0`, `reconnect=0`; copia YUV ~1,8 ms sin espejo y ~6 ms con espejo en las muestras posteriores a la actualización. Ambos quedan holgadamente por debajo de los 33,3 ms de un cuadro a 30 FPS.

La captura DirectShow bajo esta sesión llegó a ~29,03–29,20 FPS mientras el productor seguía a 30. La cadencia dependía del `Sleep(1)` del proceso consumidor, que Windows puede redondear a ~15,6 ms. El filtro ahora solicita resolución de temporizador de 1 ms durante la captura y la libera al cerrar; también reintenta antes una copia concurrente rechazada. Compilado con `winmm.lib`, registrado y probado: **300 cuadros 1280×720, 300 hashes distintos, 29,927 FPS**. Los relojes y la llegada por Wi-Fi conservan pequeñas variaciones; los contadores reflejan el ritmo medido, no un FPS fijado artificialmente.
# Perfiles seleccionables — validación local 2026-10-03

Esta sección sustituye el perfil fijo de las mediciones históricas anteriores, conservadas como referencia.

## Calidad y ancho de banda

| Perfil | H.264 objetivo | Pacing sostenido del transporte | Nivel AVC Baseline |
| --- | ---: | ---: | --- |
| 1280×720 / 30 FPS | 6 Mbps | 8 Mbps | 3.1 |
| 1280×720 / 60 FPS | 10 Mbps | 14 Mbps | 3.2 |
| 1920×1080 / 30 FPS | 12 Mbps | 16 Mbps | 4.0 |

Referencia consultada: [recomendaciones SDR de YouTube](https://support.google.com/youtube/answer/1722171?hl=es): 5 Mbps para 720p30, 7,5 Mbps para 720p60 y 8 Mbps para 1080p30. Es una referencia de subida con perfil High/B-frames, no un requisito de JyroCam. Se eligió margen adicional para nuestro Baseline de baja latencia, GOP de un segundo y escenas con movimiento: 6/10/12 Mbps. En el Motorola el codec Qualcomm confirmó CBR. Codecs sin CBR conservan VBR. No hay un bitrate universal que garantice toda escena o red.

El pacing conserva margen para IDR sin perder NAL predictivas; no limita los picos físicos de paquetes TCP. Conviene disponer de al menos ~12/20/24 Mbps **útiles y estables** de teléfono a PC para los tres perfiles respectivamente; es margen recomendado, no una velocidad mínima medida. Preferir Wi-Fi 5 GHz cercano al router; la velocidad anunciada del enlace no es throughput útil. NDI y MJPEG tienen caudales propios, diferentes del H.264 Android-PC.

## Mediciones reales

Motorola edge 30 fusion, Android 14, Qualcomm `c2.qti.avc.encoder`; PC Ethernet `192.168.1.81`, teléfono Wi-Fi `192.168.1.87`. Cada fila mide ~60 segundos después de calentamiento. USB confirma peer `127.0.0.1`; Wi-Fi confirma peer `192.168.1.87` aun con ADB conectado para controlar las pruebas. La matriz de emuladores se ejecutó separadamente de las mediciones físicas.

| Perfil / enlace / espejo | FPS decode | FPS JPEG | FPS render ACK | H.264 medio Mbps | Cola FFmpeg máx. muestreada |
| --- | ---: | ---: | ---: | ---: | ---: |
| 720p30 USB, sin espejo | 30,008 | 30,008 | 30,008 | 6,000 | 24032 B |
| 720p30 Wi-Fi, espejo | 30,013 | 30,013 | 30,013 | 5,997 | 0 B |
| 720p60 USB, sin espejo, empaquetado final | 60,023 | 60,023 | 59,956 | 9,942 | 19568 B |
| 720p60 Wi-Fi, sin espejo | 60,019 | 60,019 | 60,002 | 9,940 | 0 B |
| 720p60 Wi-Fi, espejo | 60,007 | 60,007 | 60,007 | 9,985 | 0 B |
| 1080p30 USB, sin espejo | 30,006 | 30,006 | 30,006 | 11,996 | 52400 B |
| 1080p30 Wi-Fi, espejo, empaquetado final | 29,994 | 30,011 | 29,994 | 12,008 | 0 B |

Todas las filas aprobaron: cero reconexiones durante la ventana, decoder sin error y contadores de error NDI/DirectShow sin incrementos. Máximos de bitrate por muestra de 2 s: 6,244 / 10,058 / 12,357 Mbps para USB 720p30/720p60/1080p30, dentro de sus márgenes. Los picos breves de cola no crecieron sostenidamente. Las llamadas al emisor NDI se verificaron; no se midió un receptor NDI independiente.

DirectShow real (FFmpeg, **300 cuadros / 300 hashes distintos** por ensayo): 720p30 USB **29,975 FPS**, 720p60 Wi-Fi **60,137 FPS**, 720p60 USB empaquetado final **60,016 FPS**, 1080p30 Wi-Fi empaquetado final **30,011 FPS**. El buffer predeterminado de FFmpeg solo admitía aproximadamente un cuadro 720p y descartaba muestras a 60 FPS; el harness ahora reserva cuatro cuadros y usa copia de paquetes para MD5, sin recodificación. La DLL sigue la llegada de cuadros nuevos; solo aplica limitación de cadencia si el consumidor pide 30 a un productor de 60.

No se midió latencia absoluta cámara-pantalla, autonomía térmica durante horas ni todas las condiciones de iluminación/interferencia. Los resultados son del dispositivo/red descritos; no confundir la configuración o una cola vacía con entrega garantizada en cualquier hardware.

## Integridad y reproducción

- `npm test`: parsers partidos, dimensiones BGRA/JPEG de los tres perfiles, publicación/color de memoria compartida, HTTP persistente y MP4 decodificable con dimensiones/FPS correctos en cada perfil.
- Android: `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug` aprobados. Matriz API 24–33/35/36/37.0/37.2: 4/4 por imagen; detalles en `../CamStreamApp/COMPATIBILITY.md`.
- DLL C++ compilada, registrada por hash y ejecutable empaquetado generado con `npm run build`. APK final instalado en el Motorola; prueba final del empaquetado a 720p60 USB y 1080p30 Wi-Fi.
- Elegir perfil antes de transmitir. Tras cambiar resolución, reabrir el consumidor DirectShow con el formato correspondiente. La grabación activa se finaliza al cambiar de perfil.

```powershell
# Requiere el APK y su APK de instrumentación instalados, escritorio en 8080.
# Variables opcionales: ANDROID_SERIAL, ADB, JYROCAM_LAN_IP.
node test/profile-live.js 720p30 usb false 60
node test/profile-live.js 720p30 wifi true 60
node test/profile-live.js 720p60 usb false 60
node test/profile-live.js 720p60 wifi true 60
node test/profile-live.js 1080p30 usb false 60
$env:JYROCAM_TEST_DIRECTSHOW='1'
node test/profile-live.js 1080p30 wifi true 60
```

Evidencia JSON, logs Android e instrumentación en `%TEMP%/opencode/jyrocam-<perfil>-<enlace>-mirror-<true|false>*`. Estos datos locales no se incluyen en el portable. La validación inicial utilizó un empaquetado local 1.0.2 con el código nuevo; la distribución de estos cambios corresponde a **1.0.3**, con recompilación de ambos extremos. Notas en `../RELEASE_NOTES_1.0.3.md` y hashes en `../checksums-1.0.3.sha256`; los descargables históricos se conservan.

---
