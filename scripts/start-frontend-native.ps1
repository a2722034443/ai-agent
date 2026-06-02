param(
    [int]$Port = 5173
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot = Split-Path -Parent $PSScriptRoot
$frontendDir = Join-Path $repoRoot "frontend"
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
    Write-Host "Loaded environment from .env"
}

function Test-CommandExists {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Missing command: $Name"
    }
}

Test-CommandExists "node"
Test-CommandExists "npm"
Import-DotEnv -Path $envFile

Push-Location $frontendDir
try {
    if (-not (Test-Path "node_modules")) {
        Write-Host "frontend/node_modules not found. Running npm install..."
        npm install
    }

    Write-Host "Starting Vite frontend on http://localhost:$Port"
    npx vite --host 127.0.0.1 --port $Port
}
finally {
    Pop-Location
}
