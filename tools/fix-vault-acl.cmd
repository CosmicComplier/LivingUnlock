@echo off
icacls C:\ProgramData\WindowsLockPin /inheritance:r /grant:r "NT AUTHORITY\SYSTEM:(OI)(CI)F" /grant:r "BUILTIN\Administrators:(OI)(CI)F"
icacls C:\ProgramData\WindowsLockPin\* /inheritance:r /grant:r "NT AUTHORITY\SYSTEM:F" /grant:r "BUILTIN\Administrators:F"
