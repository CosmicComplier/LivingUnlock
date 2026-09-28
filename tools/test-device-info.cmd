@echo off
setlocal
cd /d "%~dp0.."
for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "LIVING_VS=%%i"
call "%LIVING_VS%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
cl /nologo /std:c++17 /EHsc /W4 /WX /MT /utf-8 /Iwindows\common /Iwindows\broker tests\device_info_test.cpp windows\common\pairing_crypto.cpp windows\common\broker_protocol.cpp windows\broker\rfcomm_server.cpp /Fobuild\ /Febuild\device_info_test.exe /link bcrypt.lib ws2_32.lib bthprops.lib advapi32.lib
if errorlevel 1 exit /b 1
build\device_info_test.exe
