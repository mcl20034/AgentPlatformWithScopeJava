param([string]$Version = (Get-Date -Format 'yyyyMMdd-HHmmss'), [switch]$SkipTests, [switch]$SkipInstall)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$output = Join-Path $projectRoot "release\agent-platform-$Version"
if (Test-Path -LiteralPath $output) { throw "Release already exists: $output" }

$maven = if ($env:MAVEN_HOME) { Join-Path $env:MAVEN_HOME 'bin\mvn.cmd' } else { 'mvn.cmd' }
Push-Location "$projectRoot\backend"
try { & $maven clean package $(if ($SkipTests) { '-DskipTests' }); if ($LASTEXITCODE) { throw 'Backend build failed' } } finally { Pop-Location }
Push-Location "$projectRoot\frontend"
try { if (-not $SkipInstall) { npm ci; if ($LASTEXITCODE) { throw 'npm ci failed' } }; npm run build; if ($LASTEXITCODE) { throw 'Frontend build failed' } } finally { Pop-Location }

New-Item -ItemType Directory -Force -Path "$output\backend", "$output\frontend" | Out-Null
$jar = Get-ChildItem "$projectRoot\backend\target\*.jar" | Where-Object Name -NotLike '*.original' | Select-Object -First 1
if (-not $jar) { throw 'Backend jar not found' }
Copy-Item $jar.FullName "$output\backend\agent-platform.jar"
Copy-Item "$projectRoot\frontend\dist\*" "$output\frontend" -Recurse
Copy-Item "$projectRoot\deploy" "$output\deploy" -Recurse
Copy-Item "$projectRoot\docs\第七迭代-部署交付与运行保障.md" "$output\DEPLOYMENT.md"
(Get-FileHash "$output\backend\agent-platform.jar" -Algorithm SHA256).Hash.ToLowerInvariant() + '  backend/agent-platform.jar' | Set-Content "$output\SHA256SUMS"
Write-Host "Release created: $output"
