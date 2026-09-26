@echo off
setlocal
set "DLL=%~dp0virtual-cam\bin\CamStreamVirtualCam.dll"

if not exist "%DLL%" (
  echo ERROR: No se encontro la DLL "%DLL%"
  echo Compilala primero ejecutando build.bat en la carpeta virtual-cam
  pause
  exit /b 1
)

echo Registrando %DLL% como camara virtual DirectShow...
regsvr32 /u /s "%DLL%" 2>nul
regsvr32 /s "%DLL%"
if errorlevel 1 (
  echo Fallo el registro. Intenta ejecutar como Administrador.
  pause
  exit /b 1
)

echo.
echo OK. CamStream Virtual Camera registrada como camara de Windows.
echo Para usarla en Zoom, Teams, etc. seleccionala en la lista de camaras.
echo.
pause