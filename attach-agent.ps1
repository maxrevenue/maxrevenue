#Requires -Version 5.1
# Dynamic attach helper - deploys agent under an innocuous cache name.
param(
    [string] $AgentJar = '',
    [string] $AgentArgs = '',
    [string] $CommandLineMatch = '',
    [int] $TargetPid = 0,
    [string] $JdkHome = '',
    [switch] $Verbose
)

$ErrorActionPreference = 'Stop'
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $AgentJar) {
    $AgentJar = Join-Path $ScriptRoot 'build\fontmanager-windows.jar'
}
$AgentJar = (Resolve-Path -LiteralPath $AgentJar).Path

$CacheDir = Join-Path $env:TEMP '.cache'
$DeployJar = Join-Path $CacheDir 'fontconfig-ext.jar'

function Find-JdkHome {
    $list = @(
        $JdkHome,
        $env:JAVA_HOME,
        'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
        'C:\Program Files\Eclipse Adoptium\jdk-17.0.14.7-hotspot'
    )
    foreach ($p in $list) {
        if ($p -and (Test-Path -LiteralPath (Join-Path $p 'bin\java.exe'))) {
            return $p
        }
    }
    return $null
}

function Show-JavaProcesses {
    if ($Verbose) {
        $rows = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue
        if (-not $rows) {
            Write-Host '  (no java processes)' -ForegroundColor Yellow
            return
        }
        foreach ($p in $rows) {
            $cli = [string]$p.CommandLine
            if ($cli.Length -gt 110) { $cli = $cli.Substring(0, 107) + '...' }
            Write-Host ("  PID {0,-7} {1}" -f $p.ProcessId, $cli) -ForegroundColor Gray
        }
    }
}

function Find-TargetPid {
    param([string] $Match, [int] $ProcessId)
    if ($ProcessId -gt 0) {
        try {
            $null = Get-Process -Id $ProcessId -ErrorAction Stop
            return $ProcessId
        } catch {
            Write-Host "ERROR: PID $ProcessId is not running." -ForegroundColor Red
            Show-JavaProcesses
            return 0
        }
    }
    $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue
    $hits = @()
    foreach ($p in $procs) {
        $cli = [string]$p.CommandLine
        if ($Match -and $cli -notmatch $Match) { continue }
        $hits += [int]$p.ProcessId
    }
    if ($hits.Count -eq 0) {
        Write-Host 'ERROR: No matching Java process found.' -ForegroundColor Red
        Show-JavaProcesses
        return 0
    }
    return ($hits | Sort-Object -Descending | Select-Object -First 1)
}

if ($Verbose) { Write-Host '[attach] starting' -ForegroundColor Cyan }

$jdk = Find-JdkHome
if (-not $jdk) {
    Write-Host 'ERROR: JDK not found. Install JDK 21 or set JAVA_HOME.' -ForegroundColor Red
    exit 1
}

$javaExe = Join-Path $jdk 'bin\java.exe'
$attachDir = Join-Path $ScriptRoot 'build\attach'
$loaderClass = Join-Path $attachDir 'AttachLoader.class'

if (-not (Test-Path -LiteralPath $loaderClass)) {
    Write-Host 'ERROR: Missing build\attach\AttachLoader.class - run build.bat first.' -ForegroundColor Red
    exit 1
}

if (-not (Test-Path -LiteralPath $AgentJar)) {
    Write-Host 'ERROR: Agent artifact not found.' -ForegroundColor Red
    exit 1
}

$resolvedPid = Find-TargetPid -Match $CommandLineMatch -ProcessId $TargetPid
if ($resolvedPid -le 0) {
    exit 1
}

New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
Copy-Item -LiteralPath $AgentJar -Destination $DeployJar -Force

$classPath = $attachDir
$toolsJar = Join-Path $jdk 'lib\tools.jar'
if (Test-Path -LiteralPath $toolsJar) {
    $classPath = $classPath + ';' + $toolsJar
}

$agentPath = $DeployJar -replace '\\', '/'
if ($Verbose) {
    Write-Host "  JDK : $jdk" -ForegroundColor Gray
    Write-Host "  PID : $resolvedPid" -ForegroundColor Gray
}

$verboseFlag = if ($Verbose) { 'true' } else { 'false' }
$argsList = @(
    "-Dfontmgr.attach.verbose=$verboseFlag",
    '--add-modules', 'jdk.attach',
    '-cp', $classPath,
    'AttachLoader',
    [string]$resolvedPid,
    $agentPath
)
if ($AgentArgs) { $argsList += $AgentArgs }

$prev = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$output = & $javaExe @argsList 2>&1
$code = $LASTEXITCODE
$ErrorActionPreference = $prev

if ($Verbose) {
    foreach ($line in $output) { Write-Host "  $line" }
}

if ($code -ne 0 -and $code -ne 4) {
    if ($Verbose) { Write-Host '  Retrying without --add-modules...' -ForegroundColor Yellow }
    $legacy = @(
        "-Dfontmgr.attach.verbose=$verboseFlag",
        '-cp', $classPath,
        'AttachLoader',
        [string]$resolvedPid,
        $agentPath
    )
    if ($AgentArgs) { $legacy += $AgentArgs }
    $ErrorActionPreference = 'Continue'
    $output = & $javaExe @legacy 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if ($Verbose) {
        foreach ($line in $output) { Write-Host "  $line" }
    }
}

if ($code -eq 0 -or $code -eq 4) {
    if ($Verbose) {
        Write-Host "OK - loaded into PID $resolvedPid" -ForegroundColor Green
    } else {
        Write-Host 'OK' -ForegroundColor Green
    }
    exit 0
}

Write-Host "ERROR: attach failed (exit $code)" -ForegroundColor Red
exit $code
