@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT=%~dp0"
set "EXE1=%ROOT%dist\CamStreamDesktop-win32-x64\CamStreamDesktop.exe"
set "EXE2=%ROOT%dist\win-unpacked\CamStreamDesktop.exe"
set "EXE="

if exist "%EXE1%" set "EXE=%EXE1%"
if "%EXE%"=="" if exist "%EXE2%" set "EXE=%EXE2%"

echo === CamStream Desktop Launcher ===
echo Raiz: %ROOT%
echo Buscando binario...

if exist "%EXE%" goto :LAUNCH_EXE

echo Binario no encontrado en dist\win-unpacked.
echo Iniciando con Electron desde el codigo fuente...

pushd "%ROOT%"
if not exist "node_modules" (
  echo Instalando dependencias (npm install)...
  call npm install
  if errorlevel 1 goto :ERROR
)
call npm start
set "RC=%errorlevel%"
popd
goto :END

:LAUNCH_EXE
echo Encontrado: %EXE%
echo Iniciando aplicacion...
start "" "%EXE%"
set "RC=0"
goto :END

:ERROR
set "RC=%errorlevel%"
echo ERROR durante el inicio (codigo %RC%).

:END
echo.
if not "%RC%"=="0" pause
endlocal & exit /b %RC%