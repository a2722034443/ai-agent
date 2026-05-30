param(
    [string]$RedisHost,
    [int]$RedisPort = 0,
    [int]$TimeoutMs = 1500
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env"

function Import-DotEnv {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
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
}

Import-DotEnv -Path $envFile

if ([string]::IsNullOrWhiteSpace($RedisHost)) {
    $RedisHost = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { "localhost" }
}
if ($RedisPort -le 0) {
    $RedisPort = if ($env:REDIS_PORT) { [int]$env:REDIS_PORT } else { 6379 }
}

$client = [System.Net.Sockets.TcpClient]::new()
try {
    $connectTask = $client.ConnectAsync($RedisHost, $RedisPort)
    if (-not $connectTask.Wait($TimeoutMs)) {
        throw "Redis connection timed out: ${RedisHost}:$RedisPort"
    }

    $stream = $client.GetStream()
    $stream.ReadTimeout = $TimeoutMs
    $stream.WriteTimeout = $TimeoutMs

    # RESP: *1\r\n$4\r\nPING\r\n
    $payload = [byte[]](42, 49, 13, 10, 36, 52, 13, 10, 80, 73, 78, 71, 13, 10)
    $stream.Write($payload, 0, $payload.Length)

    $buffer = New-Object byte[] 64
    $read = $stream.Read($buffer, 0, $buffer.Length)
    $response = [System.Text.Encoding]::ASCII.GetString($buffer, 0, $read)

    if (-not $response.StartsWith("+PONG")) {
        throw "Redis responded, but not with PONG. Response: $response"
    }

    Write-Host "Redis OK: ${RedisHost}:$RedisPort responded PONG"
}
finally {
    $client.Dispose()
}
