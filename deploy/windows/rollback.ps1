param([Parameter(Mandatory=$true)][string]$Version)
$ErrorActionPreference = 'Stop'
$root = 'C:\ProgramData\AgentPlatform'
$target = "$root\releases\$Version"
if (-not (Test-Path "$target\backend\agent-platform.jar")) { throw "Release not found: $target" }
$winsw = "$root\service\AgentPlatform.exe"
& $winsw stop
Remove-Item -LiteralPath "$root\current" -Force
New-Item -ItemType Junction -Path "$root\current" -Target $target | Out-Null
& $winsw start
Start-Sleep -Seconds 4
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' | Out-Null
Write-Host "Rolled back to $Version. Database migrations are forward-only."
