@echo off
setlocal

set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" (
  echo No se encontro vcvars64.bat en "%VCVARS%"
  exit /b 1
)

call "%VCVARS%" >nul 2>&1
if errorlevel 1 (
  call "%VCVARS%"
)

set "SRC=%~dp0"
set "OUT=%~dp0bin"

if not exist "%OUT%" mkdir "%OUT%"

cl /nologo /W3 /O2 /EHsc /std:c++17 /D_WIN32_WINNT=0x0601 ^
   /I"%WindowsSdkDir%Include\%WindowsSDKVersion%um" ^
   /I"%WindowsSdkDir%Include\%WindowsSDKVersion%shared" ^
   /I"%VCToolsInstallDir%include" ^
   /LD /Fe:"%OUT%\JyroCamVirtualCam.dll" ^
   "%SRC%dllmain.cpp" "%SRC%filter.cpp" "%SRC%output_pin.cpp" "%SRC%shared_memory.cpp" ^
   /link /DEF:"%SRC%CamStreamVirtualCam.def" strmiids.lib ole32.lib oleaut32.lib user32.lib advapi32.lib winmm.lib

if errorlevel 1 (
  echo Compilacion FALLIDA
  exit /b 1
)

echo Compilacion OK: %OUT%\JyroCamVirtualCam.dll
endlocal
