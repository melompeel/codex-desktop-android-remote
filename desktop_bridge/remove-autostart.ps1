[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$shortcutPath = Join-Path ([Environment]::GetFolderPath("Startup")) "Codex Remote Bridge.lnk"

if (Test-Path -LiteralPath $shortcutPath) {
    Remove-Item -LiteralPath $shortcutPath
    Write-Host "Codex Remote Bridge autostart removed."
} else {
    Write-Host "Codex Remote Bridge autostart is not installed."
}
