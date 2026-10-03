# JyroCam – App Android

Aplicación nativa Android vigente que transmite la cámara del celular a JyroCam Desktop (Electron). El servidor `server.js` de la raíz pertenece al prototipo web anterior.

## Estructura

```
CamStreamApp/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── .gitignore
├── README.md
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/anomaly/camstream/
        │   ├── MainActivity.kt     # Pantalla principal con preview y controles
        │   ├── StreamService.kt   # Foreground service con CameraX
        │   └── FrameUploader.kt   # H.264 por POST persistente a CamStream Desktop
        └── res/...
```

## Requisitos

- **Android Studio compatible con AGP 8.13.2**, o compilación por terminal con el wrapper incluido
- **JDK 17** (incluido en Android Studio)
- Celular con **Android 7.0 (API 24)** o superior
- `CamStreamDesktop` corriendo en el PC (recibe H.264 en el puerto 8080)

## Compilación

1. Abre Android Studio → **File → Open** → selecciona la carpeta `CamStreamApp`.
2. Espera a que Gradle sincronice (descargará dependencias la primera vez).
3. Conecta tu celular por USB con **Depuración USB** activada
   (Opciones de desarrollador → Depuración USB).
4. Pulsa **Run ▶** en Android Studio y elige tu dispositivo.
5. La primera vez, Android Studio te pedirá instalar el SDK 36 si no lo tienes. El proyecto usa CameraX 1.6.2, AGP 8.13.2, Kotlin 2.2.21 y el wrapper Gradle 8.13/JDK 17.

Para generar el APK 1.0.2 de depuración, desde `CamStreamApp/` ejecuta `.\gradlew.bat :app:assembleDebug`; queda en `app/build/outputs/apk/debug/app-debug.apk` y conserva el ID `com.anomaly.camstream.debug`. El wrapper verifica el SHA256 de Gradle. Si usas Android Studio, selecciona la variante debug. Los APK en `app/build/` no están versionados.

### Compilar una variante de publicación

En la terminal, dentro de `CamStreamApp/`:

```powershell
.\gradlew.bat :app:assembleRelease
```

El APK queda en `app/build/outputs/apk/release/`.
La variante release requiere una firma adecuada antes de distribuirla. La compilación de desarrollo probada es `assembleDebug`; usa el APK publicado en Releases para distribuir una versión verificada.

## Uso

1. En el PC, abre `CamStreamDesktop`. No ejecutes `server.js` a la vez: usa el mismo puerto pero no recibe H.264.

2. **Por USB**, con depuración activada y el celular autorizado en `adb devices`, ejecuta en el PC:
   ```powershell
   C:\Android\platform-tools\adb.exe reverse tcp:8080 tcp:8080
   ```
   En Android usa `http://127.0.0.1:8080`. Después de desconectar/reconectar el cable hay que repetir `adb reverse`.
   **Por Wi-Fi**, usa la URL con la IP del PC que muestra `CamStreamDesktop`; ambos deben estar en la misma red.

3. En el celular, abre **JyroCam**, comprueba la URL y concede el permiso de cámara.
4. Pulsa **Iniciar transmisión**.

El video aparece en el visor del PC (`http://IP:8080/`) casi en tiempo real.

## Cómo funciona

- `MainActivity` muestra un preview y guarda la URL del servidor en `SharedPreferences`.
- `StreamService` es un **foreground service** (obligatorio en Android 14) que mantiene la cámara activa incluso con la pantalla apagada.
- `CameraX` (`ImageAnalysis`) entrega frames YUV → `MediaCodec` los codifica a H.264 → `FrameUploader` los envía en orden por una conexión `POST /stream-h264` persistente. `/upload-h264` queda para compatibilidad con escritorios anteriores.
- `CamStreamDesktop` decodifica H.264 y muestra los fotogramas en su visor, incluso sin NDI.

## Configuración

Perfil vigente: captura y salida **1280×720 a 30 FPS**, orientación de referencia fija y botón Rotar oculto/deshabilitado. Los selectores muestran el perfil fijo; se pueden cambiar cámara y espejo.

El codificador solicita **CBR 6000 kbps** cuando el hardware lo admite (confirmado en `c2.qti.avc.encoder` del Motorola edge 30 fusion). El emisor limita el caudal sostenido H.264 a **8000 kbps**, mantiene el orden de NAL y aplica contrapresión en vez de descartarlas cuando la cola está llena. SPS/PPS se repiten en cada keyframe para recuperar una conexión interrumpida. La captura exige 1280×720; si la cámara no ofrece el tamaño se informa el error, no se reduce silenciosamente.

Pruebas reales de 720p30 y comandos de reproducción: [`CamStreamDesktop/PERFORMANCE.md`](../CamStreamDesktop/PERFORMANCE.md).

El espejo utiliza empaquetado YUV por filas con buffers reutilizados para conservar 30 FPS. Se comprobaron Wi-Fi con/sin espejo y una prueba corta con pantalla apagada. Los locks CPU/Wi-Fi se mantienen durante la transmisión y se liberan al detenerla.

Compatibilidad funcional ejecutada desde Android 7 hasta Android 17 (4 KB y 16 KB), con las condiciones del emulador documentadas en [`COMPATIBILITY.md`](COMPATIBILITY.md). Se conserva `minSdk 24` y `targetSdk 34`; las APIs del servicio se seleccionan según la versión. CameraX se actualizó para manejar perfiles de cámara nuevos y MediaCodec tiene parada segura bajo contrapresión.

## Permisos usados (Android 14)

- `CAMERA` — capturar frames
- `INTERNET` — enviar al servidor
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA` — servicio en primer plano con tipo cámara
- `POST_NOTIFICATIONS` — notificación del servicio (Android 13+)
- `WAKE_LOCK` — mantener CPU activa durante transmisión

## Solución de problemas

- **No se conecta**: verifica que el celular y el PC están en el mismo Wi-Fi y que el firewall de Windows permite el puerto 8080.
- **Pantalla negra en el visor**: abre `http://IP:8080/` directamente en el navegador del PC para descartar problemas del servidor.
- **App cerrada por el sistema**: Android 14 puede matar foreground services en condiciones extremas. Reduce la calidad/FPS para que consuma menos.
