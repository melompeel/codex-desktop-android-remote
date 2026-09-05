[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$launcher = Join-Path $PSScriptRoot "launch-codex-remote.ps1"
$startupDirectory = [Environment]::GetFolderPath("Startup")
$shortcutPath = Join-Path $startupDirectory "Codex Remote Bridge.lnk"
$powershell = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $powershell
$shortcut.Arguments = "-NoLogo -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$launcher`" -DoNotLaunchCodex"
$shortcut.WorkingDirectory = $PSScriptRoot
$shortcut.Description = "Start Codex Remote Bridge when signing in to Windows"
$shortcut.Save()

Write-Host "Codex Remote Bridge autostart installed: $shortcutPath"
Write-Host "It will run hidden after the next Windows sign-in and attach when Codex Desktop opens."
