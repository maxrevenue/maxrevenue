#Requires -Version 5.1
# Rebuild Roatz HUD with PK Loadouts and launch the fresh jpackage image.
# Run from your git clone (e.g. Desktop\RoatzBot).
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $Root

Write-Host '=== Roatz HUD rebuild ===' -ForegroundColor Cyan
Write-Host "Folder: $Root"

$branch = (git rev-parse --abbrev-ref HEAD 2>$null)
$commit = (git rev-parse --short HEAD 2>$null)
Write-Host "Git: $branch @ $commit"

$panel = Join-Path $Root 'src\com\sun\java\fontmgr\swap\SwapperPanel.java'
if (-not (Test-Path -LiteralPath $panel)) {
    Write-Host 'ERROR: SwapperPanel.java missing — wrong folder?' -ForegroundColor Red
    exit 1
}
$src = Get-Content -LiteralPath $panel -Raw
if ($src -notmatch 'PK Loadouts') {
    Write-Host 'ERROR: source has no PK Loadouts. Run:' -ForegroundColor Red
    Write-Host '  git fetch origin'
    Write-Host '  git checkout cursor/bluemoon-ice-ags-de09'
    Write-Host '  git pull'
    exit 1
}
Write-Host 'Source OK: PK Loadouts present' -ForegroundColor Green

Write-Host 'Stopping old Roatz / Roat attach leftovers...' -ForegroundColor Yellow
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -match '^(Roatz|javaw?)\.exe$' -and
        $_.CommandLine -match 'Roatz|fontconfig-ext|fontmanager-windows|roat-rl'
    } |
    ForEach-Object {
        Write-Host ("  kill pid {0} {1}" -f $_.ProcessId, $_.Name)
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
Start-Sleep -Seconds 1

$cacheJar = Join-Path $env:TEMP '.cache\fontconfig-ext.jar'
if (Test-Path -LiteralPath $cacheJar) {
    Remove-Item -LiteralPath $cacheJar -Force -ErrorAction SilentlyContinue
    Write-Host "Cleared $cacheJar" -ForegroundColor Yellow
}

Write-Host 'Building jpackage image (this takes a bit)...' -ForegroundColor Yellow
& (Join-Path $Root 'gradlew.bat') --stop | Out-Null
& (Join-Path $Root 'gradlew.bat') clean jpackageImage --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host 'BUILD FAILED' -ForegroundColor Red
    exit $LASTEXITCODE
}

$exe = Join-Path $Root 'build\jpackage\Roatz\Roatz.exe'
$agent = Join-Path $Root 'build\jpackage\Roatz\app\agent.jar'
if (-not (Test-Path -LiteralPath $exe)) {
    Write-Host "ERROR: missing $exe" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path -LiteralPath $agent)) {
    Write-Host "ERROR: missing $agent" -ForegroundColor Red
    exit 1
}

$agentInfo = Get-Item -LiteralPath $agent
$agentKb = [math]::Round($agentInfo.Length / 1KB)
Write-Host ''
Write-Host 'Ready.' -ForegroundColor Green
Write-Host ("  Exe:   {0}" -f $exe)
Write-Host ("  Agent: {0} ({1} KB, {2})" -f $agent, $agentKb, $agentInfo.LastWriteTime)
Write-Host ''
Write-Host 'After Attach, Swapper must show:' -ForegroundColor Cyan
Write-Host "  - title 'Gear Swapper · v1.0.1'"
Write-Host "  - 'PK Loadouts (switch full gear sets here)' + dropdown"
Write-Host '  - New / Save As / Rename / Delete'
Write-Host "If you still see 'Swapper Hub', close Roat fully and Play again."
Write-Host ''

Start-Process -FilePath $exe
Write-Host 'Launched Roatz. Press Play, log in, then Attach.' -ForegroundColor Green
