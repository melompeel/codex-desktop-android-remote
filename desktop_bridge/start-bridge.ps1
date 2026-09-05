[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8766,

    [ValidateSet("0.0.0.0", "127.0.0.1")]
    [string]$ListenAddress = "0.0.0.0",

    [string]$DataDirectory = (Join-Path $env:APPDATA "OneSCodexRemote"),

    [string]$WhisperServerUrl = $env:WHISPER_SERVER_URL
)

$ErrorActionPreference = "Stop"

$node = Get-Command node -ErrorAction Stop
$majorVersion = [int](& $node.Source -p "process.versions.node.split('.')[0]")
if ($majorVersion -lt 22) {
    throw "Node.js 22 or newer is required. Current major version: $majorVersion"
}

$listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($listener) {
    $existingProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)" -ErrorAction SilentlyContinue
    $health = try {
        Invoke-RestMethod -Uri "http://127.0.0.1:$Port/v1/health" -TimeoutSec 2
    } catch {
        $null
    }
    $isBridge =
        $health.ok -eq $true -and
        $null -ne $health.compatibility -and
        $existingProcess.Name -eq "node.exe" -and
        $existingProcess.CommandLine -match "desktop_bridge[\\/]+dist[\\/]index\.js"

    if (-not $isBridge) {
        throw "TCP port $Port is in use by another process ($($listener.OwningProcess)); it was not stopped."
    }

    Write-Host "Existing Codex Remote Bridge detected on port $Port (PID $($listener.OwningProcess)). Restarting..."
    Stop-Process -Id $listener.OwningProcess -ErrorAction Stop

    $deadline = (Get-Date).AddSeconds(10)
    do {
        Start-Sleep -Milliseconds 200
        $remainingListener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
            Select-Object -First 1
    } while ($remainingListener -and (Get-Date) -lt $deadline)

    if ($remainingListener) {
        throw "The previous Codex Remote Bridge did not release TCP port $Port within 10 seconds."
    }
}

Push-Location $PSScriptRoot
try {
    if (-not (Test-Path (Join-Path $PSScriptRoot "node_modules"))) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed with exit code $LASTEXITCODE" }
    }

    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw "Bridge build failed with exit code $LASTEXITCODE" }

    $env:BRIDGE_HOST = $ListenAddress
    $env:BRIDGE_PORT = $Port.ToString()
    $env:BRIDGE_DATA_DIR = $DataDirectory
    if ($WhisperServerUrl) {
        $env:WHISPER_SERVER_URL = $WhisperServerUrl
    } else {
        Remove-Item Env:WHISPER_SERVER_URL -ErrorAction SilentlyContinue
    }

    Write-Host "No Windows Firewall rule is created by this script."
    & $node.Source (Join-Path $PSScriptRoot "dist\index.js")
    if ($LASTEXITCODE -ne 0) { throw "Bridge exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
