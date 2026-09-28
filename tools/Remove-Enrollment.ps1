#Requires -RunAsAdministrator
[CmdletBinding(SupportsShouldProcess)]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$vault = Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'WindowsLockPin'
$expected = [IO.Path]::GetFullPath((Join-Path $env:ProgramData 'WindowsLockPin'))
$resolved = [IO.Path]::GetFullPath($vault)
if ($resolved -ne $expected) { throw 'Unexpected enrollment directory path.' }
if (-not (Test-Path -LiteralPath $resolved)) {
    Write-Host 'No enrollment data exists.'
    return
}
$directory = Get-Item -LiteralPath $resolved -Force
if (-not $directory.PSIsContainer -or ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Enrollment path is not a normal directory.'
}
$entries = @(Get-ChildItem -LiteralPath $resolved -Force)
foreach ($entry in $entries) {
    if ($entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        $entry.Name -notmatch '^S-1-(?:[0-9]+-)+[0-9]+\.(?:bin|lock|bin\.pending)$') {
        throw 'Unexpected entry in enrollment directory; no data was removed.'
    }
}
if (-not $PSCmdlet.ShouldProcess($resolved, "Delete $($entries.Count) WindowsLockPin enrollment files")) { return }
foreach ($entry in $entries) {
    Remove-Item -LiteralPath $entry.FullName -Force -ErrorAction Stop
}
if (@(Get-ChildItem -LiteralPath $resolved -Force).Count -ne 0) {
    throw 'Enrollment directory is not empty after deleting the validated files.'
}
Remove-Item -LiteralPath $resolved -Force -ErrorAction Stop
Write-Host 'WindowsLockPin enrollment data removed.'
