# Cámara Remota (Celular → PC)

Transmite la cámara de tu celular al PC por Wi-Fi, sin instalar apps.

## Archivos
- `server.js` — servidor Node.js (sin dependencias)
- `phone.html` — página que se abre en el celular
- `viewer.html` — visor que se abre en el PC

## Requisitos
- Node.js 14+ (ya tienes v22)
- Celular y PC en la **misma red Wi-Fi**

## Uso

1. Ejecuta el servidor:
   ```
   node server.js
   ```
   Verás algo como:
   ```
   Visor (PC):    http://localhost:8080/
   Celular:       http://192.168.1.50:8080/phone
   ```

2. En el **PC**, abre `http://localhost:8080/`

3. En el **celular**, abre la URL `/phone` que muestra la consola, concede permiso de cámara y pulsa **Iniciar transmisión**.

4. La imagen aparecerá en el visor del PC casi en tiempo real.

## Controles en el celular
- Selector de cámara (frontal/trasera)
- Calidad: Baja / Media / Alta
- FPS: 10 / 15 / 24 / 30

## Notas
- El navegador debe permitir acceso a la cámara (Chrome, Edge, Safari funcionan).
- En iOS puede ser necesario usar **HTTPS** para acceder a la cámara; en Android funciona por HTTP local.
- Si tu PC tiene firewall, permite el puerto 8080.