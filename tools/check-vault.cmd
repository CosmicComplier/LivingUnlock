@echo off
setlocal
cd /d "%~dp0.."
set "LOCKPIN_VS="
for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "LOCKPIN_VS=%%i"
if not defined LOCKPIN_VS exit /b 1
call "%LOCKPIN_VS%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
cl /nologo /std:c++17 /EHsc /W4 /O2 /MT /utf-8 /Isrc /Iwindows\common windows\tools\check_vault.cpp src\vault.cpp src\totp.cpp windows\common\phone_vault.cpp /Febuild\check_vault.exe /link bcrypt.lib crypt32.lib advapi32.lib shell32.lib ole32.lib
if errorlevel 1 exit /b 1
build\check_vault.exe
