@echo off
setlocal
set "DLL=%~dp0virtual-cam\bin\JyroCamVirtualCam.dll"

if not exist "%DLL%" (
  echo DLL no encontrada: %DLL%
  pause
  exit /b 1
)

echo Desregistrando JyroCam...
regsvr32 /u /s "%DLL%"
echo OK.
pause
