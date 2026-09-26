@echo off
setlocal
pushd "%~dp0"
set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if not exist "%VCVARS%" (
  echo No se encontro vcvars64.bat
  popd
  exit /b 1
)
call "%VCVARS%" >nul 2>&1
cl /nologo /EHsc /std:c++17 /D_WIN32_WINNT=0x0601 /I"%WindowsSdkDir%Include\%WindowsSDKVersion%um" /I"%WindowsSdkDir%Include\%WindowsSDKVersion%shared" /I"%VCToolsInstallDir%include" test-camera.cpp shared_memory.cpp /Fe:test-camera.exe /link strmiids.lib ole32.lib oleaut32.lib
if errorlevel 1 (popd & exit /b 1)
test-camera.exe
set "RESULT=%errorlevel%"
popd
exit /b %RESULT%
