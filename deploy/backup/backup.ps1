param([Parameter(Mandatory=$true)][string]$Destination, [string]$EnvironmentFile='C:\ProgramData\AgentPlatform\config\agent-platform.env')
$ErrorActionPreference='Stop'
Get-Content -LiteralPath $EnvironmentFile | ForEach-Object { $line=$_.Trim(); if($line -and -not $line.StartsWith('#')){$p=$line.Split('=',2);[Environment]::SetEnvironmentVariable($p[0],$p[1],'Process')} }
if($env:PLATFORM_DB_URL -notmatch '^jdbc:postgresql://([^/:]+)(?::(\d+))?/([^?]+)'){throw 'Unsupported PLATFORM_DB_URL'}
$hostName=$Matches[1];$port=if($Matches[2]){$Matches[2]}else{'5432'};$database=$Matches[3]
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss';$dir=Join-Path $Destination "agent-platform-$stamp";New-Item -ItemType Directory -Force -Path $dir|Out-Null
$env:PGPASSWORD=$env:PLATFORM_DB_PASSWORD
& pg_dump -h $hostName -p $port -U $env:PLATFORM_DB_USERNAME -d $database -Fc -f "$dir\database.dump";if($LASTEXITCODE){throw 'pg_dump failed'}
if(Test-Path -LiteralPath $env:PLATFORM_STORAGE_ROOT){Compress-Archive -Path "$env:PLATFORM_STORAGE_ROOT\*" -DestinationPath "$dir\storage.zip" -CompressionLevel Optimal}
Get-ChildItem $dir -File|ForEach-Object{"$((Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())  $($_.Name)"}|Set-Content "$dir\SHA256SUMS"
Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
Write-Host "Backup created: $dir"
