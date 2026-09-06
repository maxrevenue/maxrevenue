# Run script: build and launch client with font manager JAR
# Usage: Open PowerShell in the project root and run: .\run-and-launch.ps1

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Definition
Write-Host "Project root: $projectRoot"

# Build
Write-Host "Building project..."
& "$projectRoot\build.bat"
if ($LASTEXITCODE -ne 0) { Write-Error "Build failed"; exit $LASTEXITCODE }

$agentJar = Join-Path $projectRoot 'build\fontmanager-windows.jar'
if (-not (Test-Path $agentJar)) { Write-Error "Agent JAR not found: $agentJar"; exit 1 }

# Default client jar (edit if different)
$clientJar = Join-Path $projectRoot 'game.jar'
if (-not (Test-Path $clientJar)) {
    Write-Warning "Client JAR not found at $clientJar. Please update the script to point to your client JAR."
    Write-Host "Looking for any .jar in project root as fallback..."
    $found = Get-ChildItem -Path $projectRoot -Filter *.jar | Select-Object -First 1
    if ($found) { $clientJar = $found.FullName; Write-Host "Using $clientJar" } else { Write-Error "No client JAR found"; exit 1 }
}

# Launch with agent
Write-Host "Launching client with agent..."
$java = "java"
$agentPath = "-javaagent:`"$agentJar`""
$jarArg = "-jar `"$clientJar`""

Write-Host "$java $agentPath $jarArg"
Start-Process -FilePath $java -ArgumentList $agentPath, $jarArg -NoNewWindow
Write-Host "Client launched. Enable file logging with -Dagent.filelog=true for diagnostics."