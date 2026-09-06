param([string]$BridgeScript, [string]$Scenario, [switch]$ThroughLauncher)
$ErrorActionPreference = 'Stop'
$harnessState = @{ stopped = $false; started = $false }
$script:LASTEXITCODE = 0
function Get-Command { [pscustomobject]@{ Source = 'Invoke-TestNode' } }
function Invoke-TestNode {
    if ($args[0] -eq '-p') { return '22' }
    $harnessState.started = $true
}
function Get-NetTCPConnection {
    if (-not $harnessState.stopped) { [pscustomobject]@{ OwningProcess = 12345 } }
}
function Get-CimInstance {
    $entry = Join-Path (Split-Path $BridgeScript) 'dist\index.js'
    if ($Scenario -eq 'unrelated') { $entry = 'C:\unrelated\service.js' }
    [pscustomobject]@{ Name = 'node.exe'; CommandLine = "node.exe `"$entry`"" }
}
function Invoke-RestMethod { throw 'health-request-timed-out' }
function Stop-Process { $harnessState.stopped = $true }
function Start-Sleep {}
function npm.cmd { $script:LASTEXITCODE = 0 }
$failure = $null
try {
    if ($ThroughLauncher) {
        & (Join-Path (Split-Path $BridgeScript) 'launch-codex-remote.ps1') -DoNotLaunchCodex
    } else {
        & $BridgeScript
    }
} catch { $failure = $_.Exception.Message }
[pscustomobject]@{ stopped = $harnessState.stopped; started = $harnessState.started; failure = $failure } |
    ConvertTo-Json -Compress
