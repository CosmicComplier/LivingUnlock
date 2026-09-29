[CmdletBinding()]
param([string]$DotnetPath, [string]$IsccPath)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $DotnetPath) {
    $bundled = Join-Path $PSScriptRoot 'dotnet\dotnet.exe'
    $DotnetPath = if (Test-Path $bundled) { $bundled } else { (Get-Command dotnet -ErrorAction Stop).Source }
}
if (-not $IsccPath) {
    $candidates = @(
        "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
        "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
        "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
    )
    $IsccPath = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $IsccPath) { throw 'Install Inno Setup 6 or specify -IsccPath.' }
}
Push-Location $root
try {
    & .\tools\build.cmd
    if ($LASTEXITCODE -ne 0) { throw 'Credential Provider build failed.' }
    & .\tools\build-phone-pairing-demo.cmd
    if ($LASTEXITCODE -ne 0) { throw 'Pairing tool build failed.' }
    Copy-Item .\build\phone_pairing_demo.exe .\windows\desktop\Assets\phone_pairing_demo.exe -Force
    & $DotnetPath publish .\windows\desktop\LivingUnlock.Windows.csproj -c Release -p:Platform=x64 -r win-x64 --self-contained true -p:PublishSingleFile=false -t:Rebuild -o .\build\desktop-publish
    if ($LASTEXITCODE -ne 0) { throw 'WinUI publish failed.' }
    & $IsccPath .\installer\WindowsLockPin.iss
    if ($LASTEXITCODE -ne 0) { throw 'Installer build failed.' }
} finally { Pop-Location }
