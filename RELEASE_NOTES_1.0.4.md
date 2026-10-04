# JyroCam 1.0.4 — Ajuste UI

## Descargas

- Windows x64: `JyroCam-Portable-1.0.4.exe`.
- Android 7.0 o posterior: `JyroCam-1.0.4.apk` (ID `com.anomaly.camstream.debug`, actualización de los APK anteriores).
- Integridad SHA256: `checksums-1.0.4.sha256`.

## Cambios

- Aplicación Android: el campo "URL del servidor" pasa a llamarse **Ipv4 Local PC** y solo acepta una IPv4 (`XXX.XXX.XX.XX`); al iniciar la transmisión la app antepone `http://` y agrega el puerto `8080` automáticamente.
- Aplicación Android: el control de **Calidad** se oculta (el bitrate efectivo se mantiene en función del perfil seleccionado).
- Aplicación Android: la entrada del servidor se guarda ahora como IPv4 (`server_ip`); se conserva compatibilidad con el valor legacy `server_url` extrayendo la IPv4 embebida.
- Visor de PC: el placeholder muestra únicamente la IPv4 del PC (sin `http://` ni `:8080`).
- Visor de PC: se elimina el texto "Puerto: 8080 / Por USB: ejecuta `adb reverse tcp:8080 tcp:8080` y usa `http://127.0.0.1:8080`" — la app Android ahora deriva la URL completa a partir de la IPv4 indicada.
- Visor de PC: el rótulo cambia a "Abre la app JyroCam en tu Android y configura tu dirección IPv4:".

## Compatibilidad

- Misma versión de protocolo (`X-JyroCam-Profile`) que 1.0.3; los APK 1.0.4 y los anteriores 1.0.3 siguen negociando los mismos perfiles 720p30 / 720p60 / 1080p30.
- Aplicación de PC 1.0.4 compatible con APK 1.0.3 y 1.0.4. El cambio de UI no altera el transporte HTTP/H.264.

## Validación

- APK 1.0.4 instalado y ejecutado en Motorola edge 30 fusion vía `adb install -r`: el campo `Ipv4 Local PC` muestra solo dígitos/punto, valida la IPv4 con regex y construye la URL al iniciar. `999.1.1.1` se rechaza con "Ingresa una IPv4 válida (ej. 192.168.1.50)"; `192.168.1.50` arranca la transmisión.
- Bloque de Calidad ya no aparece en pantalla; "Resolución / FPS" sigue mostrando los perfiles soportados por la cámara.
- Visor de PC: el placeholder sólo imprime la IPv4 local; el mensaje de error "Puerto X ocupado" se mantiene si hay conflicto con otro proceso.
- Suite de escritorio (`npm test`) sigue pasando en los tres perfiles BGRA/JPEG.

## Uso y actualización

1. Actualiza el APK y el portable de PC a 1.0.4.
2. En el celular escribe **solo** la IPv4 del PC (por ejemplo `192.168.1.50`) en `Ipv4 Local PC`. La app completará la URL como `http://192.168.1.50:8080`.
3. Cierra consumidores de cámara virtual, pulsa **Actualizar cámara virtual** y vuelve a abrirlos al primer arranque de 1.0.4.
4. Wi-Fi: ambos dispositivos deben estar en la misma red. La aplicación Android no soporta `adb reverse` desde la UI; si trabajas sólo por USB, mantén el comportamiento documentado en 1.0.3 (`adb reverse tcp:8080 tcp:8080` e introduce `127.0.0.1` en el campo Ipv4).
5. Elige **Resolución / FPS** antes de iniciar. Detén la transmisión para cambiar perfil.

Mismas IDs, CLSID y nombre de memoria compartida. Las versiones 1.0.3 y anteriores permanecen en GitHub Releases.