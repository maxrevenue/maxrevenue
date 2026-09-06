# ============================================================
#  Client launcher (login-safe attach after in-game)
# ============================================================
param(
    [switch] $Attach,
    [switch] $Premain,
    [switch] $Official,
    [switch] $SkipUpdate
)

$ErrorActionPreference = "Continue"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

$BuiltAgentJar = Join-Path $ScriptDir "build\fontmanager-windows.jar"
$BuiltAgentJarNew = Join-Path $ScriptDir "build\fontmanager-windows-new.jar"
if ((Test-Path -LiteralPath $BuiltAgentJarNew) -and -not (Test-Path -LiteralPath $BuiltAgentJar)) {
    $BuiltAgentJar = $BuiltAgentJarNew
} elseif ((Test-Path -LiteralPath $BuiltAgentJarNew) -and (Test-Path -LiteralPath $BuiltAgentJar)) {
    if ((Get-Item -LiteralPath $BuiltAgentJarNew).LastWriteTime -gt (Get-Item -LiteralPath $BuiltAgentJar).LastWriteTime) {
        $BuiltAgentJar = $BuiltAgentJarNew
    }
}
$AgentCacheDir = Join-Path $env:TEMP ".cache"
$AgentJar      = Join-Path $AgentCacheDir "fontconfig-ext.jar"
$GameJava      = "C:\Program Files (x86)\roatpkz_runelite\jre-64\bin\java.exe"
if (-not (Test-Path -LiteralPath $GameJava)) {
    $GameJava = "C:\Program Files (x86)\roatpkz_runelite\jre\bin\java.exe"
}
$RpkzDir    = Join-Path $env:USERPROFILE "rpkzclient"
$SavedJar   = Join-Path $ScriptDir "roat-rl-saved.jar"
$LauncherJar = Join-Path $RpkzDir "RoatPkzLauncher.jar"

Clear-Host
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Client Launcher" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# ── Official launcher mode (recommended when login is broken) ──────────────
if ($Official) {
    if (-not (Test-Path -LiteralPath $LauncherJar)) {
        Write-Host "  ERROR: Official launcher not found at $LauncherJar" -ForegroundColor Red
        Read-Host "Press Enter to exit"
        exit 1
    }
    Write-Host "Opening official Roat PKz launcher..." -ForegroundColor Yellow
    Write-Host "  1) Let it update the client if prompted" -ForegroundColor Gray
    Write-Host "  2) Log in normally" -ForegroundColor Gray
    Write-Host "  3) When in-game, run:" -ForegroundColor Cyan
    Write-Host "     .\attach-agent.ps1 -CommandLineMatch 'roat-rl'" -ForegroundColor White
    Start-Process -FilePath $GameJava -ArgumentList @("-jar", "`"$LauncherJar`"")
    exit 0
}

# ── Build agent ───────────────────────────────────────────────────────────
Write-Host "[1/5] Building agent..." -ForegroundColor Yellow
& (Join-Path $ScriptDir "gradlew.bat") buildAll --console=plain | Out-Null
if (-not (Test-Path -LiteralPath $BuiltAgentJar)) {
    Write-Host "  BUILD FAILED" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}
New-Item -ItemType Directory -Force -Path $AgentCacheDir | Out-Null
Copy-Item -LiteralPath $BuiltAgentJar -Destination $AgentJar -Force
$AttachLoaderClass = Join-Path $ScriptDir "build\attach\AttachLoader.class"
if (-not (Test-Path -LiteralPath $AttachLoaderClass)) {
    Write-Host "  BUILD FAILED: missing build\attach\AttachLoader.class" -ForegroundColor Red
    Write-Host "  Re-run: .\gradlew.bat buildAll" -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}
Write-Host "  Agent OK ($([math]::Round((Get-Item $AgentJar).Length / 1KB)) KB)" -ForegroundColor Green
Write-Host "  AttachLoader OK" -ForegroundColor Green

# ── Locate game JAR ───────────────────────────────────────────────────────
Write-Host "[2/5] Locating game JAR..." -ForegroundColor Yellow

function Find-LiveRoatJar {
    param([string]$Dir)
    $hit = Get-ChildItem -LiteralPath $Dir -Filter "roat-rl-*.jar" -Force -ErrorAction SilentlyContinue |
           Where-Object { $_.Length -gt 1MB -and $_.Name -notmatch 'saved' } |
           Sort-Object LastWriteTime -Descending |
           Select-Object -First 1
    if (-not $hit) {
        $local = Join-Path $Dir "roat-rl-local.jar"
        if (Test-Path -LiteralPath $local) {
            $hit = Get-Item -LiteralPath $local -Force -ErrorAction SilentlyContinue |
                   Where-Object { $_.Length -gt 1MB }
        }
    }
    return $hit
}

function Clear-SavedJarHidden {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $item = Get-Item -LiteralPath $Path -Force
    $item.Attributes = $item.Attributes -band (-bnot [IO.FileAttributes]::Hidden)
}

function Save-LiveJarCopy {
    param($Source)
    Copy-Item -LiteralPath $Source.FullName -Destination $SavedJar -Force
    Clear-SavedJarHidden $SavedJar
    $copied = Get-Item -LiteralPath $SavedJar -Force -ErrorAction SilentlyContinue
    if ($copied) { return $copied }
    return $Source
}

$LiveJar = Find-LiveRoatJar $RpkzDir
$GameJar = $null

if ($LiveJar) {
    try {
        $GameJar = Save-LiveJarCopy $LiveJar
        Write-Host "  Using $($LiveJar.Name) from rpkzclient" -ForegroundColor Gray
    } catch {
        $GameJar = $LiveJar
    }
} elseif (Test-Path -LiteralPath $SavedJar) {
    $GameJar = Get-Item -LiteralPath $SavedJar -Force
    Write-Host "  Using saved copy (no live JAR in rpkzclient)" -ForegroundColor Yellow
} else {
    Write-Host "  No client JAR found." -ForegroundColor Red
    Write-Host "  Run: .\launch.ps1 -Official   (open real Roat launcher first)" -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}

# Stuck download lock from a half-finished Roat update blocks fresh jars.
$DownloadLock = Join-Path $RpkzDir "roat-rl-download.lock"
if (Test-Path -LiteralPath $DownloadLock) {
    try {
        Remove-Item -LiteralPath $DownloadLock -Force -ErrorAction Stop
        Write-Host "  Cleared stuck roat-rl-download.lock" -ForegroundColor Yellow
    } catch {
        Write-Host "  Could not clear download lock (client still open?)" -ForegroundColor Yellow
    }
}

$jarAgeDays = [math]::Round(((Get-Date) - $GameJar.LastWriteTime).TotalDays, 1)
# Roat may not push a new pack for weeks — only block when clearly ancient.
if ($jarAgeDays -gt 21 -and -not $SkipUpdate) {
    Write-Host ""
    Write-Host "  WARNING: Client JAR is $jarAgeDays days old." -ForegroundColor Red
    Write-Host "  Stale clients often hang forever at LOGGING_IN." -ForegroundColor Red
    Write-Host ""
    Write-Host "  Opening official launcher to refresh the client..." -ForegroundColor Yellow
    Write-Host "  1) Let it update / download" -ForegroundColor Gray
    Write-Host "  2) Log in once, wait until in-game, then close" -ForegroundColor Gray
    Write-Host "  3) Run:  .\launch.ps1 -Attach" -ForegroundColor White
    Write-Host ""
    $ans = Read-Host "Open Official now? [Y/n]  (or type s = skip / continue anyway)"
    if ($ans -match '^[sS]') {
        Write-Host "  Continuing with current JAR..." -ForegroundColor Yellow
    } elseif ($ans -notmatch '^[nN]') {
        if (Test-Path -LiteralPath $LauncherJar) {
            Start-Process -FilePath $GameJava -ArgumentList @("-jar", "`"$LauncherJar`"")
        } else {
            Start-Process -FilePath "C:\Program Files (x86)\roatpkz_runelite\Roat Pkz.exe"
        }
        exit 0
    } else {
        exit 0
    }
} elseif ($jarAgeDays -gt 5 -and -not $SkipUpdate) {
    Write-Host "  Note: client JAR is $jarAgeDays days old (OK unless login hangs)." -ForegroundColor Gray
    Write-Host "  Refresh anytime:  .\launch.ps1 -Official" -ForegroundColor Gray
}

$jarMb = [math]::Round($GameJar.Length / 1MB, 1)
Write-Host "  Game JAR: $($GameJar.Name) ($jarMb MB, $jarAgeDays days old)" -ForegroundColor Green

# ── Auth params (must match the JAR being launched) ───────────────────────
$client_md5 = (Get-FileHash -Algorithm MD5 -LiteralPath $GameJar.FullName).Hash.ToLower()
$launch_ts  = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
Write-Host "  MD5: $client_md5" -ForegroundColor Gray

if (-not (Test-Path -LiteralPath $GameJava)) {
    Write-Host "  ERROR: java.exe not found at $GameJava" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# ── Network sanity check ──────────────────────────────────────────────────
Write-Host "[3/5] Checking login server..." -ForegroundColor Yellow
try {
    $tcp = Test-NetConnection maingame.roatpkz.ps -Port 43595 -WarningAction SilentlyContinue
    if ($tcp.TcpTestSucceeded) {
        Write-Host "  maingame.roatpkz.ps:43595 reachable" -ForegroundColor Green
    } else {
        Write-Host "  WARNING: cannot reach login server (firewall/VPN?)" -ForegroundColor Red
    }
} catch {
    Write-Host "  Could not test login port" -ForegroundColor Yellow
}

# ── Kill only our previous client instances ───────────────────────────────
Write-Host "[4/5] Stopping old Roat client instances..." -ForegroundColor Yellow
Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'roat-rl|roat-rl-saved|fontconfig-ext' } |
    ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Host "  Stopped PID $($_.ProcessId)" -ForegroundColor Gray
    }
Start-Sleep 2

# ── Launch ────────────────────────────────────────────────────────────────
$modeLabel = if ($Premain) { "PREMAIN javaagent" } elseif ($Attach) { "vanilla + attach after login" } else { "vanilla (login-safe)" }
Write-Host "[5/5] Launching ($modeLabel)..." -ForegroundColor Yellow

$gameArgs = @(
    "-Dsun.java2d.dpiaware=true",
    "-Dsun.java2d.uiScale=1.0",
    "-Drunelite.launcher.nojvm=true",
    "-Xmx4g", "-Xms1g", "-Xss2m",
    "-XX:CompileThreshold=1500",
    "-Djna.nosys=true",
    "-XX:+UseStringDeduplication",
    "-XX:AutoBoxCacheMax=65535",
    "-Droatpkz.ac.client_md5=$client_md5",
    "-Droatpkz.ac.launch_ts=$launch_ts"
)
if ($Premain) {
    $gameArgs += "-javaagent:`"$AgentJar`""
}
$gameArgs += @("-jar", "`"$($GameJar.FullName)`"")

$proc = Start-Process -FilePath $GameJava -ArgumentList $gameArgs -PassThru
Write-Host "  Client PID: $($proc.Id)" -ForegroundColor Green
Write-Host ""

if ($Premain) {
    Write-Host "  Overlay appears in ~10s. INSERT toggles HUD." -ForegroundColor Cyan
    Write-Host "  Login stuck? Use  .\launch.ps1 -Attach  instead of -Premain" -ForegroundColor Yellow
} elseif ($Attach) {
    Write-Host "  === LOG IN NOW ===" -ForegroundColor Yellow
    Write-Host "  When in-game, press Enter to attach..." -ForegroundColor Cyan
    Read-Host
    if ($proc.HasExited) {
        Write-Host "  Client PID $($proc.Id) already exited. Start again with .\launch.ps1 -Attach" -ForegroundColor Red
    } else {
        Write-Host "  Attaching agent to PID $($proc.Id)..." -ForegroundColor Yellow
        $jdkHome = $env:JAVA_HOME
        if (Test-Path -LiteralPath "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot") {
            $jdkHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
        }
        $attachArgs = @{
            AgentJar          = $BuiltAgentJar
            CommandLineMatch  = 'roat|rpkz|RoatPkz'
            TargetPid         = $proc.Id
        }
        if ($jdkHome) { $attachArgs.JdkHome = $jdkHome }
        $attachExit = 1
        try {
            & "$ScriptDir\attach-agent.ps1" @attachArgs
            if ($null -ne $LASTEXITCODE) { $attachExit = $LASTEXITCODE }
        } catch {
            Write-Host "  Attach script error: $($_.Exception.Message)" -ForegroundColor Red
            $attachExit = 1
        }
        if ($attachExit -ne 0) {
            Write-Host "  PowerShell attach failed (exit $attachExit), trying attach-agent.cmd..." -ForegroundColor Yellow
            & cmd /c "`"$ScriptDir\attach-agent.cmd`" $($proc.Id)"
            $attachExit = $LASTEXITCODE
        }
        if ($attachExit -eq 0) {
            Write-Host "  Attached. INSERT toggles panel." -ForegroundColor Green
            Write-Host "  Rebuild: kill client fully, then .\launch.ps1 -Attach again." -ForegroundColor Gray
        } else {
            Write-Host "  Attach FAILED (exit $attachExit). Agent was NOT loaded." -ForegroundColor Red
            Write-Host "  Client still running? Try:" -ForegroundColor Yellow
            Write-Host "    .\attach-agent.cmd $($proc.Id)" -ForegroundColor White
        }
    }
} else {
    Write-Host "  Log in, then attach:" -ForegroundColor Cyan
    Write-Host "    .\launch.ps1 -Attach" -ForegroundColor White
    Write-Host "  Or if login fails, update client first:" -ForegroundColor Yellow
    Write-Host "    .\launch.ps1 -Official" -ForegroundColor White
}

Write-Host "==========================================" -ForegroundColor Cyan
