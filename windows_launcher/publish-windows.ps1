[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$OutputName = "CodexRemote-Windows-x64"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$bridgeRoot = Join-Path $projectRoot "desktop_bridge"
$output = Join-Path $PSScriptRoot "release\$OutputName"
$node = "C:\Program Files\node-win-x64\node.exe"

if (-not (Test-Path $node)) {
    $node = (Get-Command node.exe -ErrorAction Stop).Source
}

Push-Location $bridgeRoot
try {
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw "Bridge build failed." }
} finally {
    Pop-Location
}

if (Test-Path $output) { Remove-Item $output -Recurse -Force }
& dotnet publish (Join-Path $PSScriptRoot "CodexRemoteManager.csproj") `
    -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:DebugType=None -p:DebugSymbols=false `
    --source "https://api.nuget.org/v3/index.json" `
    -o $output
if ($LASTEXITCODE -ne 0) { throw "Windows manager publish failed." }

$runtimeDirectory = Join-Path $output "runtime"
$packagedBridge = Join-Path $output "bridge"
New-Item $runtimeDirectory -ItemType Directory -Force | Out-Null
New-Item (Join-Path $packagedBridge "dist") -ItemType Directory -Force | Out-Null
Copy-Item $node (Join-Path $runtimeDirectory "node.exe")
Copy-Item (Join-Path $bridgeRoot "dist\*") (Join-Path $packagedBridge "dist") -Recurse
Copy-Item (Join-Path $bridgeRoot "package.json") $packagedBridge
Copy-Item (Join-Path $bridgeRoot "package-lock.json") $packagedBridge
Copy-Item (Join-Path $PSScriptRoot "WINDOWS_README.txt") $output

Push-Location $packagedBridge
try {
    & npm.cmd ci --omit=dev --ignore-scripts
    if ($LASTEXITCODE -ne 0) { throw "Bridge runtime dependency packaging failed." }
} finally {
    Pop-Location
}

Write-Host "Windows package created at: $output"
