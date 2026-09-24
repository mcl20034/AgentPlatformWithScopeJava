$ErrorActionPreference = 'Stop'
$root = 'C:\ProgramData\AgentPlatform'
$envFile = Join-Path $root 'config\agent-platform.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw "Missing environment file: $envFile" }

Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $parts = $line.Split('=', 2)
        if ($parts.Count -ne 2) { throw "Invalid environment line: $line" }
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], 'Process')
    }
}

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
$jar = Join-Path $root 'current\backend\agent-platform.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw "Missing application jar: $jar" }
& $java '-Xms512m' '-Xmx2g' '-XX:+ExitOnOutOfMemoryError' '-jar' $jar
exit $LASTEXITCODE
