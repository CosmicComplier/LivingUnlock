#Requires -RunAsAdministrator
[CmdletBinding(SupportsShouldProcess)]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not [Environment]::Is64BitProcess) { throw 'Run 64-bit PowerShell.' }
$project = Split-Path -Parent $PSScriptRoot
$source = Join-Path $project 'build'
$install = Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'WindowsLockPin'
$guid = '{16B44968-DC91-4F41-BB1B-30D36B3F0BCE}'
$providerKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\$guid"
$classKey = "HKLM:\SOFTWARE\Classes\CLSID\$guid"
$files = @('WindowsLockPin.dll', 'WindowsLockPinSetup.exe')
foreach ($name in $files) {
    if (-not (Test-Path -LiteralPath (Join-Path $source $name) -PathType Leaf)) { throw "Build is missing: $name" }
}
$managerSource = Join-Path $PSScriptRoot 'Manage-LivingUnlock.ps1'
if (-not (Test-Path -LiteralPath $managerSource -PathType Leaf)) { throw 'LivingUnlock manager script is missing.' }
if ((Test-Path -LiteralPath $providerKey) -or (Test-Path -LiteralPath $classKey)) {
    throw 'Provider is already registered. Uninstall it and restart Windows before replacing loaded binaries.'
}
if (Test-Path -LiteralPath $install) {
    throw "Installation directory already exists; inspect it before reuse: $install"
}
if (-not $PSCmdlet.ShouldProcess($install, 'Install experimental OTP provider alongside native Windows PIN')) { return }

# Protected directory is created before privileged executable files are copied.
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true, $false)
$admin = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
$system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$users = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-545')
$acl.SetOwner($admin)
$inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
foreach ($sid in @($admin, $system)) {
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', $inherit, 'None', 'Allow'))
}
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($users, 'ReadAndExecute', $inherit, 'None', 'Allow'))
$directory = [IO.DirectoryInfo]::new($install)
$directory.Create($acl)
foreach ($name in $files) {
    $from = Join-Path $source $name
    $to = Join-Path $install $name
    Copy-Item -LiteralPath $from -Destination $to
    if ((Get-FileHash -LiteralPath $from -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $to -Algorithm SHA256).Hash) {
        throw "Copy verification failed: $name. No login provider has been registered."
    }
}
$toolsDirectory = Join-Path $install 'tools'
New-Item -ItemType Directory -Path $toolsDirectory -ErrorAction Stop | Out-Null
$managerTarget = Join-Path $toolsDirectory 'Manage-LivingUnlock.ps1'
Copy-Item -LiteralPath $managerSource -Destination $managerTarget -ErrorAction Stop
if ((Get-FileHash -LiteralPath $managerSource -Algorithm SHA256).Hash -ne
    (Get-FileHash -LiteralPath $managerTarget -Algorithm SHA256).Hash) {
    throw 'Manager script copy verification failed. No login provider has been registered.'
}
try {
    New-Item -Path $classKey -Force | Out-Null
    $server = Join-Path $classKey 'InprocServer32'
    New-Item -Path $server -Force | Out-Null
    Set-Item -LiteralPath $server -Value (Join-Path $install 'WindowsLockPin.dll')
    New-ItemProperty -LiteralPath $server -Name ThreadingModel -PropertyType String -Value Apartment | Out-Null
    New-Item -Path $providerKey -Force | Out-Null
    Set-Item -LiteralPath $providerKey -Value 'LivingUnlock'
} catch {
    # Remove only the exact registration keys this invocation created.
    if (Test-Path -LiteralPath $providerKey) { Remove-Item -LiteralPath $providerKey }
    $server = Join-Path $classKey 'InprocServer32'
    if (Test-Path -LiteralPath $server) { Remove-Item -LiteralPath $server }
    if (Test-Path -LiteralPath $classKey) { Remove-Item -LiteralPath $classKey }
    throw
}
Write-Host 'Installed. Native PIN/password providers and passwordless settings were not changed.'
Write-Host "Run the binding tool locally as the target Windows user: $(Join-Path $install 'WindowsLockPinSetup.exe')"
Write-Host 'No account password or TOTP secret has been collected by this script.'
