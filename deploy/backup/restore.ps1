param([Parameter(Mandatory=$true)][string]$BackupDirectory,[Parameter(Mandatory=$true)][string]$EnvironmentFile,[switch]$Confirm)
$ErrorActionPreference='Stop';if(-not $Confirm){throw 'Restore writes into the configured database. Rerun with -Confirm after verifying it is empty.'}
Get-Content -LiteralPath $EnvironmentFile|ForEach-Object{$line=$_.Trim();if($line -and -not $line.StartsWith('#')){$p=$line.Split('=',2);[Environment]::SetEnvironmentVariable($p[0],$p[1],'Process')}}
if($env:PLATFORM_DB_URL -notmatch '^jdbc:postgresql://([^/:]+)(?::(\d+))?/([^?]+)'){throw 'Unsupported PLATFORM_DB_URL'}
$hostName=$Matches[1];$port=if($Matches[2]){$Matches[2]}else{'5432'};$database=$Matches[3];$dump=Join-Path $BackupDirectory 'database.dump';if(-not(Test-Path $dump)){throw 'database.dump missing'}
$manifest=Join-Path $BackupDirectory 'SHA256SUMS';if(-not(Test-Path $manifest)){throw 'SHA256SUMS missing'}
Get-Content $manifest|ForEach-Object{$parts=$_ -split '\s+',2;$file=Join-Path $BackupDirectory $parts[1];if(-not(Test-Path $file)-or (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant()-ne $parts[0]){throw "Checksum failed: $($parts[1])"}}
$env:PGPASSWORD=$env:PLATFORM_DB_PASSWORD;& pg_restore --exit-on-error --no-owner --no-privileges -h $hostName -p $port -U $env:PLATFORM_DB_USERNAME -d $database $dump;if($LASTEXITCODE){throw 'pg_restore failed'}
$archive=Join-Path $BackupDirectory 'storage.zip';if(Test-Path $archive){New-Item -ItemType Directory -Force -Path $env:PLATFORM_STORAGE_ROOT|Out-Null;if(Get-ChildItem $env:PLATFORM_STORAGE_ROOT -Force|Select-Object -First 1){throw 'Storage target is not empty'};Expand-Archive $archive $env:PLATFORM_STORAGE_ROOT}
Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue;Write-Host 'Restore completed. Start the application and run the acceptance checks.'
