param(
    [string]$MysqlHost,
    [int]$MysqlPort = 0,
    [string]$RedisHost,
    [int]$RedisPort = 0
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $repoRoot "backend"
$envFile = Join-Path $repoRoot ".env"

function Import-DotEnv {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        Write-Warning ".env not found at $Path. Using process environment and defaults."
        return
    }

    Get-Content -Path $Path | ForEach-Object {
        $line = $_.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
            return
        }
        $pair = $line -split "=", 2
        if ($pair.Count -ne 2) {
            return
        }

        $name = $pair[0].Trim()
        $value = $pair[1].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if (-not [string]::IsNullOrWhiteSpace($name)) {
            [System.Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
    Write-Host "Loaded environment from .env"
}

function Test-CommandExists {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Missing command: $Name"
    }
}

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port,
        [int]$TimeoutMs = 1500
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($HostName, $Port)
        if (-not $task.Wait($TimeoutMs)) {
            throw "Connection timed out: ${HostName}:$Port"
        }
    }
    finally {
        $client.Dispose()
    }
}

Write-Host "Checking native backend prerequisites..."

Import-DotEnv -Path $envFile

if ([string]::IsNullOrWhiteSpace($MysqlHost)) {
    $MysqlHost = if ($env:DB_HOST) { $env:DB_HOST } else { "localhost" }
}
if ($MysqlPort -le 0) {
    $MysqlPort = if ($env:DB_PORT) { [int]$env:DB_PORT } else { 3306 }
}
if ([string]::IsNullOrWhiteSpace($RedisHost)) {
    $RedisHost = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { "localhost" }
}
if ($RedisPort -le 0) {
    $RedisPort = if ($env:REDIS_PORT) { [int]$env:REDIS_PORT } else { 6379 }
}

Test-CommandExists "java"
Test-TcpPort -HostName $MysqlHost -Port $MysqlPort
Write-Host "MySQL TCP OK: ${MysqlHost}:$MysqlPort"

& (Join-Path $PSScriptRoot "check-redis-native.ps1") -RedisHost $RedisHost -RedisPort $RedisPort

if ([string]::IsNullOrWhiteSpace($env:AMAP_WEB_SERVICE_KEY)) {
    Write-Warning "AMAP_WEB_SERVICE_KEY is empty. Real POI/route planning will be blocked."
}
if ([string]::IsNullOrWhiteSpace($env:MIMO_API_KEY)) {
    Write-Warning "MIMO_API_KEY is empty. LLM-first planning will be unavailable."
}

Write-Host "Starting Spring Boot backend on http://localhost:8080"
Push-Location $backendDir
try {
    .\mvnw.cmd -s .\.mvn\settings.xml spring-boot:run
}
finally {
    Pop-Location
}
