[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8766,

    [switch]$DoNotLaunchCodex
)

$ErrorActionPreference = "Stop"
$codexAppId = "OpenAI.Codex_2p2nqsd0c76g0!App"
$bridgeScript = Join-Path $PSScriptRoot "start-bridge.ps1"

function Test-CodexDesktopRunning {
    return [bool](Get-Process -Name "ChatGPT" -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -like "*\OpenAI.Codex_*" } |
        Select-Object -First 1)
}

function Test-BridgeRunning {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $listener) { return $false }

    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/v1/health" -TimeoutSec 2
        return $health.ok -eq $true -and $null -ne $health.compatibility
    } catch {
        # start-bridge verifies the process identity before recovering a hung Bridge.
        return $false
    }
}

if (-not $DoNotLaunchCodex -and -not (Test-CodexDesktopRunning)) {
    Start-Process "explorer.exe" -ArgumentList "shell:AppsFolder\$codexAppId"
}

if (Test-BridgeRunning) {
    Write-Host "Codex Remote Bridge is already running on port $Port."
    exit 0
}

& $bridgeScript -Port $Port -ListenAddress "0.0.0.0"
