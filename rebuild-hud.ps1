#Requires -Version 5.1
# Rebuild Roatz HUD with PK Loadouts and launch the fresh jpackage image.
# Run from your git clone (e.g. Desktop\RoatzBot).
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $Root

function Invoke-QuietTaskkill {
    param([Parameter(Mandatory = $true)][string[]]$TaskkillArgs)
    # taskkill prints "ERROR: The process ... not found" to stderr when idle.
    # With $ErrorActionPreference=Stop that aborts the whole script — swallow it.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & taskkill.exe @TaskkillArgs 1>$null 2>$null | Out-Null
    } catch {
        # ignore — process already gone is fine
    } finally {
        $ErrorActionPreference = $prev
    }
}

function Stop-RoatzLocks {
    Write-Host 'Stopping old Roatz / Roat clients...' -ForegroundColor Yellow

    foreach ($im in @('Roatz.exe', 'RoatzBot.exe')) {
        Invoke-QuietTaskkill -TaskkillArgs @('/F', '/IM', $im, '/T')
    }

    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $name = [string]$_.Name
            $cmd = [string]$_.CommandLine
            $exe = [string]$_.ExecutablePath
            if ($name -match '^(?i)Roatz\.exe$') { return $true }
            if ($exe -match '(?i)[\\/]Roatz\.exe$') { return $true }
            if ($exe -match '(?i)build[\\/]jpackage[\\/]Roatz') { return $true }
            if ($name -match '^(?i)(java|javaw)\.exe$' -and
                $cmd -match '(?i)roat-rl|roat-rl-saved|rpkz|roatpkz|roatpkz_runelite|fontconfig-ext|fontmanager-windows|\\Roatz\\') {
                return $true
            }
            return $false
        } |
        ForEach-Object {
            Write-Host ("  kill pid {0} {1}" -f $_.ProcessId, $_.Name)
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            Invoke-QuietTaskkill -TaskkillArgs @('/F', '/PID', "$($_.ProcessId)", '/T')
        }

    # Give Windows time to release file handles on Roatz.exe
    Start-Sleep -Seconds 2
}

function Remove-TreeWithRetry {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$Attempts = 8
    )
    if (-not (Test-Path -LiteralPath $Path)) { return $true }
    for ($i = 1; $i -le $Attempts; $i++) {
        try {
            # Rename locked exe first so jpackage can recreate the folder tree.
            $lockedExe = Join-Path $Path 'Roatz\Roatz.exe'
            if (Test-Path -LiteralPath $lockedExe) {
                $deadName = "Roatz.exe.old-$PID"
                Rename-Item -LiteralPath $lockedExe -NewName $deadName -Force -ErrorAction SilentlyContinue
            }
            Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop
            if (-not (Test-Path -LiteralPath $Path)) { return $true }
        } catch {
            Write-Host ("  delete retry {0}/{1}: {2}" -f $i, $Attempts, $_.Exception.Message) -ForegroundColor Yellow
            Stop-RoatzLocks
            Start-Sleep -Seconds ([Math]::Min(2 * $i, 8))
        }
    }
    return -not (Test-Path -LiteralPath $Path)
}

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

Stop-RoatzLocks

$cacheJar = Join-Path $env:TEMP '.cache\fontconfig-ext.jar'
$status = Join-Path $env:TEMP '.cache\fontconfig-attach.status'
if (Test-Path -LiteralPath $cacheJar) {
    Remove-Item -LiteralPath $cacheJar -Force -ErrorAction SilentlyContinue
    Write-Host "Cleared $cacheJar" -ForegroundColor Yellow
}
if (Test-Path -LiteralPath $status) {
    Remove-Item -LiteralPath $status -Force -ErrorAction SilentlyContinue
}

$jpackageDir = Join-Path $Root 'build\jpackage'
Write-Host 'Clearing locked jpackage output...' -ForegroundColor Yellow
if (-not (Remove-TreeWithRetry -Path $jpackageDir)) {
    Write-Host 'ERROR: still cannot delete build\jpackage — something still locks Roatz.exe.' -ForegroundColor Red
    Write-Host 'Close File Explorer windows inside build\jpackage, then:' -ForegroundColor Red
    Write-Host '  taskkill /F /IM Roatz.exe /T'
    Write-Host '  powershell -ExecutionPolicy Bypass -File .\rebuild-hud.ps1'
    exit 1
}

Write-Host 'Building jpackage image (this takes a bit)...' -ForegroundColor Yellow
& (Join-Path $Root 'gradlew.bat') --stop | Out-Null
# Skip full :clean — it fails whenever anything under build\ is locked.
# jpackageImage alone is enough after we wiped build\jpackage.
& (Join-Path $Root 'gradlew.bat') jpackageImage --console=plain
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
Write-Host "  - title 'Gear Swapper · v1.0.8'"
Write-Host "  - 'PK Loadouts (switch full gear sets here)' + dropdown"
Write-Host '  - New / Save As / Rename / Delete'
Write-Host 'Launcher footer must say v1.0.8.'
Write-Host ''

Start-Process -FilePath $exe
Write-Host 'Launched Roatz. Press Play, log in, then wait for Attach (or Attach now).' -ForegroundColor Green
