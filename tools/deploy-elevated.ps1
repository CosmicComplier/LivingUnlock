#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$project = Split-Path -Parent $PSScriptRoot
$build = Join-Path $project 'build'
$sourceDll = Join-Path $build 'WindowsLockPin.dll'
$sourceManager = Join-Path $PSScriptRoot 'Manage-LivingUnlock.ps1'
$install = Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'WindowsLockPin'
$targetDll = Join-Path $install 'WindowsLockPin.dll'
$toolsDir = Join-Path $install 'tools'
$targetManager = Join-Path $toolsDir 'Manage-LivingUnlock.ps1'
$guid = '{16B44968-DC91-4F41-BB1B-30D36B3F0BCE}'
$classKey = "HKLM:\SOFTWARE\Classes\CLSID\$guid"
$providerKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\$guid"

foreach ($path in @($sourceDll, $sourceManager, $targetDll)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file is missing: $path" }
}
$installItem = Get-Item -LiteralPath $install -Force
if (-not $installItem.PSIsContainer -or
    ($installItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Installed provider directory is not a normal directory.'
}
foreach ($key in @($classKey, $providerKey)) {
    if (-not (Test-Path -LiteralPath $key)) { throw "Provider registration is missing: $key" }
}

$backupDir = Join-Path $build ('deployment-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backupDir -ErrorAction Stop | Out-Null
$backupDll = Join-Path $backupDir 'WindowsLockPin.dll'
$backupManager = Join-Path $backupDir 'Manage-LivingUnlock.ps1'
Copy-Item -LiteralPath $targetDll -Destination $backupDll -ErrorAction Stop
$managerExisted = Test-Path -LiteralPath $targetManager -PathType Leaf
if ($managerExisted) { Copy-Item -LiteralPath $targetManager -Destination $backupManager -ErrorAction Stop }
$oldClassName = (Get-Item -LiteralPath $classKey).GetValue('')
$oldProviderName = (Get-Item -LiteralPath $providerKey).GetValue('')

try {
    if (-not (Test-Path -LiteralPath $toolsDir -PathType Container)) {
        New-Item -ItemType Directory -Path $toolsDir -ErrorAction Stop | Out-Null
    }
    Copy-Item -LiteralPath $sourceManager -Destination $targetManager -Force -ErrorAction Stop
    $managerAcl = New-Object Security.AccessControl.FileSecurity
    $managerAcl.SetSecurityDescriptorSddlForm('O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;GRGX;;;BU)')
    Set-Acl -LiteralPath $targetManager -AclObject $managerAcl
    if ((Get-FileHash -LiteralPath $sourceManager -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $targetManager -Algorithm SHA256).Hash) {
        throw 'Manager script copy verification failed.'
    }

    Copy-Item -LiteralPath $sourceDll -Destination $targetDll -Force -ErrorAction Stop
    if ((Get-FileHash -LiteralPath $sourceDll -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $targetDll -Algorithm SHA256).Hash) {
        throw 'Provider DLL copy verification failed.'
    }
    Set-Item -LiteralPath $classKey -Value 'LivingUnlock'
    Set-Item -LiteralPath $providerKey -Value 'LivingUnlock'
    @(
        'DEPLOYED=LivingUnlock'
        'DLL_SHA256=' + (Get-FileHash -LiteralPath $targetDll -Algorithm SHA256).Hash
        'MANAGER_SHA256=' + (Get-FileHash -LiteralPath $targetManager -Algorithm SHA256).Hash
        'BACKUP=' + $backupDir
    ) | Set-Content -LiteralPath (Join-Path $build 'deploy-result.txt') -Encoding UTF8
} catch {
    $failure = $_
    try {
        Copy-Item -LiteralPath $backupDll -Destination $targetDll -Force
        if ($managerExisted) {
            Copy-Item -LiteralPath $backupManager -Destination $targetManager -Force
        } elseif (Test-Path -LiteralPath $targetManager) {
            Remove-Item -LiteralPath $targetManager -Force
        }
        Set-Item -LiteralPath $classKey -Value $oldClassName
        Set-Item -LiteralPath $providerKey -Value $oldProviderName
    } catch {
        throw "Deployment failed and rollback also failed: $($failure.Exception.Message); $($_.Exception.Message)"
    }
    throw $failure
}
