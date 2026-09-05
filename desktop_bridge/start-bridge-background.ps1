[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$bridgeScript = Join-Path $PSScriptRoot "start-bridge.ps1"
$powershell = Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe"
$arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$bridgeScript`""

$process = Start-Process `
    -FilePath $powershell `
    -ArgumentList $arguments `
    -WorkingDirectory $PSScriptRoot `
    -WindowStyle Hidden `
    -PassThru

Start-Sleep -Seconds 1
if ($process.HasExited) {
    throw "Codex Remote Bridge background process exited during startup."
}

Write-Host "Codex Remote Bridge is starting in the background. This window can be closed."
