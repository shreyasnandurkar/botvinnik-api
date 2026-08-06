# Runs the gateway-overhead benchmark end to end: Postgres, the stub provider, the
# gateway, provisioning, both load phases, teardown. Everything it starts, it stops.
#
#   .\bench\run-bench.ps1
#   .\bench\run-bench.ps1 -Stream -Levels 10,50 -Duration 30

param(
    [string]   $JavaHome = "$env:USERPROFILE\.jdks\temurin-25.0.3-1",
    [int[]]    $Levels   = @(10, 50, 200),
    [int]      $Duration = 30,
    [int]      $Warmup   = 10,
    [int]      $StubPort = 11500,
    [int]      $Port     = 4000,
    [switch]   $Stream,
    [string]   $Out      = ""
)

$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$java  = Join-Path $JavaHome 'bin\java.exe'
$javac = Join-Path $JavaHome 'bin\javac.exe'
$base  = "http://127.0.0.1:$Port"
$classes = Join-Path $env:TEMP 'botvinnik-bench-classes'
if ($Out -eq "") {
    if ($Stream) { $Out = "$root\bench\results-streaming.md" } else { $Out = "$root\bench\results-nonstreaming.md" }
}

if (-not (Test-Path $java)) { throw "JDK 25 not found at $JavaHome — pass -JavaHome" }

$stubProcess = $null
$gatewayProcess = $null

try {
    Write-Host '==> Postgres' -ForegroundColor Cyan
    $pg = docker ps -a --filter 'name=botvinnik-pg' --format '{{.Names}} {{.State}}'
    if (-not $pg) {
        docker run -d --name botvinnik-pg -e POSTGRES_USER=botvinnik -e POSTGRES_PASSWORD=botvinnik `
            -e POSTGRES_DB=botvinnik -p 5432:5432 postgres:17-alpine | Out-Null
    } elseif ($pg -notmatch 'running') {
        docker start botvinnik-pg | Out-Null
    }
    do { Start-Sleep -Milliseconds 500 } until ((docker exec botvinnik-pg pg_isready -U botvinnik) -match 'accepting')

    $jar = Join-Path $root 'target\botvinnikAPI-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path $jar)) {
        Write-Host '==> Building the gateway' -ForegroundColor Cyan
        $env:JAVA_HOME = $JavaHome
        & (Join-Path $root 'mvnw.cmd') -q package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'build failed' }
    }

    Write-Host '==> Compiling the harness' -ForegroundColor Cyan
    & $javac -d $classes "$root\bench\StubProvider.java" "$root\bench\LoadGen.java"
    if ($LASTEXITCODE -ne 0) { throw 'harness compile failed' }

    Write-Host '==> Stub provider' -ForegroundColor Cyan
    $stubProcess = Start-Process -FilePath $java -PassThru -WindowStyle Hidden `
        -ArgumentList @('-cp', $classes, 'StubProvider', $StubPort)

    Write-Host '==> Gateway' -ForegroundColor Cyan
    # A placeholder Gemini key satisfies boot; the benchmark never touches that provider.
    $env:GEMINI_API_KEY = 'bench-placeholder'
    if (-not $env:GATEWAY_ENCRYPTION_KEY) {
        $bytes = New-Object byte[] 32
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $env:GATEWAY_ENCRYPTION_KEY = [Convert]::ToBase64String($bytes)
    }
    $gatewayProcess = Start-Process -FilePath $java -PassThru -WindowStyle Hidden `
        -ArgumentList @('-jar', $jar)

    $deadline = (Get-Date).AddSeconds(90)
    do {
        Start-Sleep -Milliseconds 500
        try { $up = Invoke-RestMethod "$base/v1/health" -TimeoutSec 2 } catch { $up = $null }
        if ((Get-Date) -gt $deadline) { throw 'gateway did not come up' }
    } until ($up)

    Write-Host '==> Provisioning' -ForegroundColor Cyan
    $existing = Invoke-RestMethod "$base/v1/providers"
    if (-not ($existing | Where-Object { $_.name -eq 'bench-stub' })) {
        Invoke-RestMethod "$base/v1/providers" -Method Post -ContentType 'application/json' `
            -Body (@{ name = 'bench-stub'; type = 'ollama'; base_url = "http://127.0.0.1:$StubPort" } | ConvertTo-Json) | Out-Null
    }
    # No rate limit and no spend cap: this measures the gateway, not Bucket4j saying no.
    $key = (Invoke-RestMethod "$base/v1/apikeys" -Method Post -ContentType 'application/json' `
        -Body (@{ name = 'bench' } | ConvertTo-Json)).key

    Write-Host '==> Load' -ForegroundColor Cyan
    $loadArgs = @('-cp', $classes, 'LoadGen', '--gateway', $base, '--stub', "http://127.0.0.1:$StubPort",
                  '--key', $key, '--levels', ($Levels -join ','), '--duration', $Duration,
                  '--warmup', $Warmup, '--out', $Out)
    if ($Stream) { $loadArgs += '--stream' }
    & $java $loadArgs
}
finally {
    Write-Host '==> Teardown' -ForegroundColor Cyan
    foreach ($p in @($gatewayProcess, $stubProcess)) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    }
}
