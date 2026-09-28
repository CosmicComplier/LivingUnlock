@echo off
setlocal
cd /d "%~dp0.."
set "LOCKPIN_VS="
for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "LOCKPIN_VS=%%i"
if not defined LOCKPIN_VS exit /b 1
call "%LOCKPIN_VS%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
if not exist build mkdir build
cl /nologo /std:c++17 /EHsc /W4 /WX /O2 /MT /utf-8 /Iwindows\common /Iwindows\broker /Ithird_party\qrcodegen windows\common\broker_protocol.cpp windows\common\phone_messages.cpp windows\common\pairing_crypto.cpp windows\common\phone_vault.cpp windows\broker\session_store.cpp windows\broker\rfcomm_server.cpp windows\tools\pairing_demo.cpp third_party\qrcodegen\qrcodegen.cpp /Fobuild\ /Febuild\phone_pairing_demo.exe /link bcrypt.lib ws2_32.lib bthprops.lib crypt32.lib advapi32.lib shell32.lib ole32.lib user32.lib
exit /b %errorlevel%
