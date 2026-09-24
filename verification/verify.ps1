param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCommand = 'mvn.cmd',
    [string[]]$MavenArgs = @('test')
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Provide -JavaHome pointing to a JDK 21 installation.'
}
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
try {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $previousPath
    & $MavenCommand -B -ntp -s (Join-Path $PSScriptRoot 'settings.xml') -gs (Join-Path $PSScriptRoot 'settings.xml') -f (Join-Path $PSScriptRoot 'pom.xml') @MavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Verification failed with Maven exit code $LASTEXITCODE" }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
}
