param([Parameter(Mandatory=$true)][string]$ReleaseDirectory)
$ErrorActionPreference = 'Stop'
$root = 'C:\ProgramData\AgentPlatform'
$source = (Resolve-Path -LiteralPath $ReleaseDirectory).Path
if (-not (Test-Path "$source\backend\agent-platform.jar") -or -not (Test-Path "$source\frontend\index.html")) { throw 'Invalid release directory' }

$version = Split-Path $source -Leaf
$target = Join-Path $root "releases\$version"
New-Item -ItemType Directory -Force -Path "$root\releases", "$root\data", "$root\logs", "$root\service-logs", "$root\config", "$root\secure" | Out-Null
if (-not (Test-Path -LiteralPath $target)) { Copy-Item -LiteralPath $source -Destination $target -Recurse }

$envFile = "$root\config\agent-platform.env"
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item "$target\deploy\config\agent-platform.windows.env.example" $envFile
    Write-Warning "Edit $envFile and create secure key/password files before starting the service."
    return
}

$current = "$root\current"
if (Test-Path -LiteralPath $current) { Remove-Item -LiteralPath $current -Force }
New-Item -ItemType Junction -Path $current -Target $target | Out-Null

$serviceDir = "$root\service"
New-Item -ItemType Directory -Force -Path $serviceDir | Out-Null
Copy-Item "$target\deploy\windows\launcher.ps1" "$serviceDir\launcher.ps1" -Force
Copy-Item "$target\deploy\windows\agent-platform-service.xml" "$serviceDir\AgentPlatform.xml" -Force
$winsw = "$serviceDir\AgentPlatform.exe"
if (-not (Test-Path -LiteralPath $winsw)) { throw "Place the WinSW executable at $winsw, then rerun this script." }

& $winsw stop 2>$null
& $winsw uninstall 2>$null
& $winsw install
& $winsw start
Start-Sleep -Seconds 4
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' | Out-Null
Write-Host "Installed Agent Platform $version"
