#Requires -RunAsAdministrator
[CmdletBinding()]
param([string]$OutputPath)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$vault = Join-Path ([Environment]::GetFolderPath('CommonApplicationData')) 'WindowsLockPin'
if (-not (Test-Path -LiteralPath $vault -PathType Container)) { throw 'Enrollment vault does not exist.' }
$directory = Get-Item -LiteralPath $vault -Force
if ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Enrollment vault is a reparse point.' }
$files = @(Get-ChildItem -LiteralPath $vault -Force -File)
$enrollments = @($files | Where-Object Name -Like 'S-1-*.bin')
$locks = @($files | Where-Object Name -Like 'S-1-*.lock')
$pending = @($files | Where-Object Name -Like '*.pending')
$unexpected = @($files | Where-Object Name -NotMatch '^S-1-[0-9-]+\.(bin|lock)$')
if ($enrollments.Count -ne 1) { throw "Expected one enrollment file; found $($enrollments.Count)." }
if ($pending.Count -ne 0 -or $unexpected.Count -ne 0) { throw 'Unexpected or pending enrollment files exist.' }

function Test-LockPinAcl([string]$Path) {
    $acl = Get-Acl -LiteralPath $Path
    $allowed = @('S-1-5-18', 'S-1-5-32-544')
    foreach ($rule in $acl.Access) {
        $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        if ($rule.AccessControlType -ne 'Allow' -or $sid -notin $allowed) { return $false }
    }
    $ownerSid = $acl.Owner
    try { $ownerSid = ([Security.Principal.NTAccount]$acl.Owner).Translate([Security.Principal.SecurityIdentifier]).Value } catch {}
    return $ownerSid -in $allowed
}
$aclValid = Test-LockPinAcl $vault
foreach ($file in $files) { $aclValid = $aclValid -and (Test-LockPinAcl $file.FullName) }
if (-not $aclValid) { throw 'Enrollment vault ACL is outside the expected SYSTEM/Administrators boundary.' }

$summary = [pscustomobject]@{
    VaultExists = $true
    EnrollmentFileCount = $enrollments.Count
    LockFileCount = $locks.Count
    PendingFileCount = $pending.Count
    ReparsePoint = $false
    AclValid = $true
} | ConvertTo-Json -Compress
if ($OutputPath) {
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath), $summary, [Text.UTF8Encoding]::new($false))
} else {
    $summary
}
