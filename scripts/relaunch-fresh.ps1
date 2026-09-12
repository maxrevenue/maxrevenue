#Requires -Version 5.1
<#
.SYNOPSIS
  Refresh the installed launcher from the repo build, optionally with the tick
  recorder on, then start it.

.DESCRIPTION
  The installed launcher lives at %LOCALAPPDATA%\Roatz and reads the agent sitting
  next to it. Rebuilding the repo does NOT update it, and launching it never
  rebuilds anything — so the two have to be bridged explicitly. This does the whole
  sequence in one go because doing it by hand keeps getting half-done:

    1. stop the running launcher(s) and game client
    2. copy build\jpackage\Roatz over the install
    3. set ROATZ_AGENT_FLAGS (launcher reads it, forwards to the game JVM)
    4. start the launcher

  You still click Play and log in — the agent only begins ticking once you are
  in-world, and the recording starts with it.

.EXAMPLE
  .\scripts\relaunch-fresh.ps1 -Record
#>
param(
    [switch] $Record,
    [switch] $Log,
    [switch] $KeepRunning,
    [string] $Api = "https://roatz-license.alec-5c7.workers.dev"
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$src  = Join-Path $repo 'build\jpackage\Roatz'
$dst  = Join-Path $env:LOCALAPPDATA 'Roatz'

if (-not (Test-Path -LiteralPath (Join-Path $src 'Roatz.exe'))) {
    Write-Host "No build at $src" -ForegroundColor Red
    Write-Host "Build it first:" -ForegroundColor Yellow
    Write-Host "  .\gradlew.bat jpackageImage `"-ProatzLicenseApi=$Api`""
    exit 1
}

# -- 1. stop anything holding the install dir --------------------------------
if (-not $KeepRunning) {
    $killed = 0
    Get-CimInstance Win32_Process -Filter "Name='Roatz.exe' or Name='java.exe' or Name='javaw.exe'" |
        Where-Object {
            $_.ExecutablePath -like '*\Roatz\*' -or
            $_.CommandLine  -match 'roatpkz|roat-rl'
        } |
        ForEach-Object {
            try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop; $killed++ } catch {}
        }
    Write-Host "Stopped $killed process(es)." -ForegroundColor DarkGray
    Start-Sleep -Seconds 2
}

# -- 2. copy the build over the install --------------------------------------
Write-Host "Copying $src -> $dst" -ForegroundColor Cyan
robocopy $src $dst /E /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) {
    Write-Host "robocopy failed with code $LASTEXITCODE (files still locked?)" -ForegroundColor Red
    Write-Host 'Close the launcher and the game and retry.' -ForegroundColor Yellow
    exit 1
}

# -- 3. prove the copy went in ----------------------------------------------
$agent = Join-Path $dst 'app\agent.jar'
$lch   = Join-Path $dst 'app\roatz-launcher.jar'
Write-Host ''
Write-Host ("installed agent        : {0}" -f (Get-Item -LiteralPath $agent).LastWriteTime)
Write-Host ("installed launcher jar : {0}  ({1} bytes)" -f `
    (Get-Item -LiteralPath $lch).LastWriteTime, (Get-Item -LiteralPath $lch).Length)

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($lch)
try {
    $hasFlags = ($zip.Entries | Where-Object { $_.FullName -like '*AgentFlags.class' }).Count -gt 0
} finally { $zip.Dispose() }
if ($hasFlags) {
    Write-Host 'AgentFlags present  : yes (ROATZ_AGENT_FLAGS supported)' -ForegroundColor Green
} else {
    Write-Host 'AgentFlags present  : NO - the install still has an older launcher' -ForegroundColor Red
}

# -- 4. flags + launch -------------------------------------------------------
$flags = @()
if ($Record) { $flags += '-Droatz.rec=true' }
if ($Log)    { $flags += '-Dagent.filelog=true'; $flags += '-Dagent.debug=true' }

if ($flags.Count) {
    $env:ROATZ_AGENT_FLAGS = ($flags -join ' ')
    Write-Host ''
    Write-Host ("ROATZ_AGENT_FLAGS = {0}" -f $env:ROATZ_AGENT_FLAGS) -ForegroundColor Cyan
    if ($Record) {
        Write-Host 'Tick recording starts when the agent attaches, i.e. after you log in.'
        Write-Host ("  {0}" -f (Join-Path $env:APPDATA 'Roatz\ticks'))
    }
    if ($Log) {
        Write-Host 'Agent log (attach, init, and any tick exception):'
        Write-Host ("  {0}" -f (Join-Path $env:TEMP '.cache\jvm-cache-log.dat'))
    }
}

Write-Host ''
Write-Host 'Starting the launcher. Click Play, then LOG IN FULLY.' -ForegroundColor Cyan
Start-Process -FilePath (Join-Path $dst 'Roatz.exe')
Write-Host ''
Write-Host 'Then verify:' -ForegroundColor DarkGray
Write-Host '  .\scripts\whats-my-weapon.ps1     # must say "agent LIVE"'
if ($Record) { Write-Host '  .\scripts\analyze-ticks.ps1       # after riding a fight' }
