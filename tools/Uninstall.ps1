#Requires -RunAsAdministrator
[CmdletBinding(SupportsShouldProcess)]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not [Environment]::Is64BitProcess) { throw 'Run 64-bit PowerShell.' }
$install = Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'WindowsLockPin'
$guid = '{16B44968-DC91-4F41-BB1B-30D36B3F0BCE}'
$providerKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\$guid"
$classKey = "HKLM:\SOFTWARE\Classes\CLSID\$guid"
$server = Join-Path $classKey 'InprocServer32'
if (Test-Path -LiteralPath $server) {
    $registered = (Get-Item -LiteralPath $server).GetValue('')
    if ($registered -ne (Join-Path $install 'WindowsLockPin.dll')) {
        throw 'Registration does not point to this installation. No changes made.'
    }
}
if (-not $PSCmdlet.ShouldProcess($install, 'Unregister WindowsLockPin; preserve native providers and encrypted enrollment')) { return }
if (Test-Path -LiteralPath $providerKey) { Remove-Item -LiteralPath $providerKey }
if (Test-Path -LiteralPath $server) { Remove-Item -LiteralPath $server }
if (Test-Path -LiteralPath $classKey) { Remove-Item -LiteralPath $classKey }
if (Test-Path -LiteralPath $install) {
    $directory = Get-Item -LiteralPath $install -Force
    if ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Installation path is a reparse point. Registration removed; files were not touched.'
    }
    foreach ($name in @('WindowsLockPin.dll', 'WindowsLockPinSetup.exe')) {
        $file = Join-Path $install $name
        if (Test-Path -LiteralPath $file -PathType Leaf) {
            try { Remove-Item -LiteralPath $file -ErrorAction Stop }
            catch { Write-Warning "File still in use; restart Windows and rerun this script: $file" }
        }
    }
    if (@(Get-ChildItem -LiteralPath $install -Force).Count -eq 0) { Remove-Item -LiteralPath $install }
}
Write-Host 'Unregistered. Native Windows sign-in remains available.'
Write-Host 'Encrypted enrollment remains in ProgramData\WindowsLockPin. See docs\OPERATIONS.md for explicit removal.'
