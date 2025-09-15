param([int]$Port = 8080, [switch]$Demo)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (-not $env:JAVA_HOME) {
        $jdkRoot = Join-Path $env:USERPROFILE '.jdks'
        $candidate = Get-ChildItem -LiteralPath $jdkRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
            Sort-Object Name | Select-Object -First 1
        if ($candidate) { $env:JAVA_HOME = $candidate.FullName }
    }
    if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw 'Install JDK 17 or newer and set JAVA_HOME before starting API Guard.'
    }
    $jar = Join-Path $projectRoot 'target\api-regression-platform-1.0.0.jar'
    if (-not (Test-Path -LiteralPath $jar)) {
        & .\mvnw.cmd -B -ntp verify
        if ($LASTEXITCODE -ne 0) { throw 'Build failed. Resolve the errors above before starting.' }
    }
    $javaCommand = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
    $launchArgs = @('-jar', $jar, "--server.port=$Port")
    if ($Demo) { $launchArgs += '--spring.profiles.active=demo' }
    & $javaCommand @launchArgs
    if ($LASTEXITCODE -ne 0) { throw "Application exited with code $LASTEXITCODE" }
} finally { Pop-Location }
