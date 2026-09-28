#Requires -RunAsAdministrator
[CmdletBinding(SupportsShouldProcess)]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not [Environment]::Is64BitProcess) { throw 'Run 64-bit PowerShell.' }
if (Get-Process -Name WindowsLockPinSetup -ErrorAction SilentlyContinue) {
    throw 'Close the binding tool before updating it.'
}
$project = Split-Path -Parent $PSScriptRoot
$source = Join-Path $project 'build\WindowsLockPinSetup.exe'
$install = Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'WindowsLockPin'
$destination = Join-Path $install 'WindowsLockPinSetup.exe'
$staging = Join-Path $install 'WindowsLockPinSetup.exe.new'
$guid = '{16B44968-DC91-4F41-BB1B-30D36B3F0BCE}'
$server = "HKLM:\SOFTWARE\Classes\CLSID\$guid\InprocServer32"
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'Build output is missing.' }
if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) { throw 'Installed binding tool is missing.' }
if (-not (Test-Path -LiteralPath $server)) { throw 'WindowsLockPin is not registered.' }
$registeredDll = (Get-Item -LiteralPath $server).GetValue('')
if ($registeredDll -ne (Join-Path $install 'WindowsLockPin.dll')) { throw 'Unexpected provider registration.' }
if (Test-Path -LiteralPath $staging) { throw 'A staging file already exists; inspect it before retrying.' }
if (-not $PSCmdlet.ShouldProcess($destination, 'Replace only the WindowsLockPin binding utility')) { return }
try {
    Copy-Item -LiteralPath $source -Destination $staging
    $expected = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    if ((Get-FileHash -LiteralPath $staging -Algorithm SHA256).Hash -ne $expected) { throw 'Staging hash mismatch.' }
    Move-Item -LiteralPath $staging -Destination $destination -Force
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $expected) { throw 'Installed hash mismatch.' }
} catch {
    if (Test-Path -LiteralPath $staging -PathType Leaf) { Remove-Item -LiteralPath $staging -Force }
    throw
}
Write-Host 'Binding utility updated. Credential provider DLL and existing enrollment were not changed.'
