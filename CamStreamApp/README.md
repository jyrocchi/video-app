# CamStream – App Android (Android 14)

Aplicación nativa Android que transmite la cámara del celular al servidor Node.js del proyecto anterior.

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
    │   └── FrameUploader.kt   # HTTP POST de H.264 al servidor
        └── res/...
```

## Requisitos

- **Android Studio** Hedgehog (2023.1) o más nuevo
- **JDK 17** (incluido en Android Studio)
- Celular con **Android 7.0 (API 24)** o superior
- `CamStreamDesktop` corriendo en el PC (recibe H.264 en el puerto 8080)

## Compilación

1. Abre Android Studio → **File → Open** → selecciona la carpeta `CamStreamApp`.
2. Espera a que Gradle sincronice (descargará dependencias la primera vez).
3. Conecta tu celular por USB con **Depuración USB** activada
   (Opciones de desarrollador → Depuración USB).
4. Pulsa **Run ▶** en Android Studio y elige tu dispositivo.
5. La primera vez, Android Studio te pedirá instalar el SDK 34 si no lo tienes.

### Generar APK firmado manualmente

En la terminal, dentro de `CamStreamApp/`:

```powershell
gradle assembleRelease
# o
gradlew.bat assembleRelease
```

El APK queda en `app/build/outputs/apk/release/`.

## Uso

1. En el PC, abre `CamStreamDesktop`. No ejecutes `server.js` a la vez: usa el mismo puerto pero no recibe H.264.

2. **Por USB**, con depuración activada y el celular autorizado en `adb devices`, ejecuta en el PC:
   ```powershell
   C:\Android\platform-tools\adb.exe reverse tcp:8080 tcp:8080
   ```
   En Android usa `http://127.0.0.1:8080`. Después de desconectar/reconectar el cable hay que repetir `adb reverse`.
   **Por Wi-Fi**, usa la URL con la IP del PC que muestra `CamStreamDesktop`; ambos deben estar en la misma red.

3. En el celular, abre **CamStream**, comprueba la URL y concede el permiso de cámara.
4. Pulsa **Iniciar transmisión**.

El video aparece en el visor del PC (`http://IP:8080/`) casi en tiempo real.

## Cómo funciona

- `MainActivity` muestra un preview y guarda la URL del servidor en `SharedPreferences`.
- `StreamService` es un **foreground service** (obligatorio en Android 14) que mantiene la cámara activa incluso con la pantalla apagada.
- `CameraX` (`ImageAnalysis`) entrega frames YUV → `MediaCodec` los codifica a H.264 → `FrameUploader` los envía en orden por `POST /upload-h264`.
- `CamStreamDesktop` decodifica H.264 y muestra los fotogramas en su visor, incluso sin NDI.

## Configuración

En la app puedes elegir:
- **Cámara**: trasera / frontal
- **Calidad**: baja / media / alta ajustan el bitrate H.264.
- **FPS objetivo**: 10 / 15 / 24 / 30

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
