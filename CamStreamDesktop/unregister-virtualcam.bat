@echo off
setlocal
set "DLL=%~dp0virtual-cam\bin\CamStreamVirtualCam.dll"

if not exist "%DLL%" (
  echo DLL no encontrada: %DLL%
  pause
  exit /b 1
)

echo Desregistrando CamStream Virtual Camera...
regsvr32 /u /s "%DLL%"
echo OK.
pause