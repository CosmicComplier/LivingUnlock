[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('AES','ChaCha20','Password')][string]$Mode,
    [Parameter(Mandatory)][string]$TextFile,
    [Parameter(Mandatory)][string]$OutputFile
)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$inputPath=(Resolve-Path -LiteralPath $TextFile).Path
$outputPath=[IO.Path]::GetFullPath($OutputFile)
if(Test-Path -LiteralPath $outputPath){throw 'Output file already exists.'}
$code=@{AES='A7';ChaCha20='C4';Password='P9'}[$Mode]
$originalPassword=$env:LIVINGUNLOCK_EGG_PASSWORD
try {
    if($Mode -eq 'Password') {
        $secure=Read-Host '彩蛋口令（不会显示）' -AsSecureString
        $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try {$env:LIVINGUNLOCK_EGG_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}
        finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr);$secure.Dispose()}
    }
    Push-Location (Join-Path $project 'android')
    try {
        & .\gradlew.bat :core:generateEgg "-PeggMode=$code" "-PeggText=$inputPath" "-PeggOutput=$outputPath"
        if($LASTEXITCODE -ne 0){throw 'QR generation failed.'}
    } finally {Pop-Location}
} finally {$env:LIVINGUNLOCK_EGG_PASSWORD=$originalPassword}
