# CamStream Desktop (con Camara Virtual)

Visor, grabador y camara virtual para el stream de CamStream (Android).

## Que hace

- Muestra el stream de tu celular en una ventana nativa de Windows
- Reemplaza al `server.js` (lo incluye internamente en el puerto 8080)
- Graba sesiones a MP4 (H.264)
- **Se registra como camara virtual en Windows** - aparece en Zoom, Teams, Discord, etc.

## Archivos

- `main.js` - proceso principal: servidor HTTP, ventana, IPC, grabacion
- `virtual-cam-writer.js` - escribe frames JPEG a memoria compartida (Node + koffi)
- `ndi-sender.js` - salida NDI opcional
- `viewer.html` / `styles.css` - UI de la ventana
- `virtual-cam/` - DLL DirectShow de camara virtual (C++)
- `virtual-cam/bin/CamStreamVirtualCam.dll` - DLL compilado (131 KB)
- `register-virtualcam.bat` - registra la DLL como camara de Windows
- `unregister-virtualcam.bat` - desregistra

## Como usar

1. **Inicia la app**:
   ```
   CamStreamDesktop-Launcher.bat
   ```
   O abre el ZIP y ejecuta el launcher.

3. **Conecta el celular**:
    - Abre la app **CamStream** en tu Android
    - Con USB y depuración autorizada, ejecuta `C:\Android\platform-tools\adb.exe reverse tcp:8080 tcp:8080` en el PC y usa `http://127.0.0.1:8080` en el celular
    - Por Wi-Fi, ingresa la URL que muestra la ventana (ej. `http://192.168.1.XX:8080`)
    - Pulsa **Iniciar transmision**

4. Pulsa **Instalar cámara virtual** en la ventana de CamStream Desktop. Se instala para el usuario actual, sin pedir permisos de administrador. Cierra y vuelve a abrir Zoom/Discord después de instalarla; selecciónala como **CamStream Virtual Camera**.

4. **Usa como camara**:
   - En Zoom / Teams / Discord / cualquier app
   - Selecciona **"CamStream Virtual Camera"** en la lista de camaras
   - La imagen de tu celular aparecera en tiempo real

## Como funciona

```
[Android CamStream] --HTTP POST H.264--> [Electron app]
                                            |
                                    [H.264 decode -> BGRA 640x480]
                                            |
                               [visor JPEG + shared memory]
                                            |
                                            v
                                  [CamStreamVirtualCam.dll]
                                  (DirectShow source filter)
                                            |
                                            v
                                  [Windows sees as webcam]
```

La DLL DirectShow lee frames del buffer compartido (`Global\CamStreamVirtualCam_Frame`)
y los entrega a cualquier aplicacion que use DirectShow (que son todas las apps de camara
en Windows).

## Limites tecnicos

- DirectShow x64; resolución fija 640x480 RGB24
- Formato RGB24 (la app decodifica JPEG y convierte)
- Sin audio (es video solamente)
- Latencia tipica: 100-200ms (depende de Wi-Fi y FPS configurado en el celular)
- La DLL corre como InprocServer32 registrado en HKCU (no necesita admin)

## Compilacion desde codigo

Si quieres recompilar la DLL:
```
cd virtual-cam
build.bat
```

Requiere MSVC Build Tools (ya tienes `C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools`).

## Desinstalacion

```
unregister-virtualcam.bat
```

Elimina las entradas del registro. No elimina la DLL ni los archivos de la app.

## Notas

- El toggle "NDI" en la app sigue funcionando si tienes NDI Tools instalado
- La app Cámara de Windows y otros clientes basados exclusivamente en Media Foundation pueden no enumerar filtros DirectShow. La implementación actual se orienta a clientes compatibles con DirectShow; soporte de Media Foundation requiere una implementación adicional.
- Si no aparece "CamStream Virtual Camera" en Zoom/Discord, reinicia la aplicación después de instalarla.
- Para grabar: pulsa "Grabar" en la app, elige ubicacion, pulsa "Detener" al terminar
