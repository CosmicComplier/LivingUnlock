#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string]$VaultDirectory = (Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'WindowsLockPin')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not [Environment]::Is64BitProcess) { throw '64-bit PowerShell is required.' }

if (-not (Test-Path -LiteralPath $VaultDirectory -PathType Container)) {
    Write-Host "Vault directory does not exist yet: $VaultDirectory"
    Write-Host 'Nothing to repair. Save credentials / pair a phone first, then run promote-*.'
    exit 0
}

# Same SDDL the installer and Manage-LivingUnlock.ps1 Publish-Record apply.
$dirSddl = 'O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)'
$fileSddl = 'O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)'

$dirAcl = New-Object Security.AccessControl.DirectorySecurity
$dirAcl.SetSecurityDescriptorSddlForm($dirSddl)
Set-Acl -LiteralPath $VaultDirectory -AclObject $dirAcl
Write-Host "Directory ACL repaired: $VaultDirectory"

$fileAcl = New-Object Security.AccessControl.FileSecurity
$fileAcl.SetSecurityDescriptorSddlForm($fileSddl)

$count = 0
Get-ChildItem -LiteralPath $VaultDirectory -File -Force | Where-Object {
    $_.Name -like '*.cred' -or $_.Name -like '*.bin' -or $_.Name -like 'phone_*.dat'
} | ForEach-Object {
    Set-Acl -LiteralPath $_.FullName -AclObject $fileAcl
    Write-Host "  repaired: $($_.Name)"
    $count++
}

Write-Host "Repaired $count record file(s) in $VaultDirectory."
Write-Host 'Re-run Manage-LivingUnlock.ps1 promote-* if you also want the per-user staging copies published.'
