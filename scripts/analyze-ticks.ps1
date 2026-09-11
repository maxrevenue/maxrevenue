#Requires -Version 5.1
<#
.SYNOPSIS
  Summarise a Roatz tick recording, with the one number that decides whether
  gear-based defensive prayer (#2) is worth defaulting on.

.DESCRIPTION
  Reads a .tsv written by TickRecorder (-Droatz.rec=true) and reports:

    * which defensive-prayer branch the HUD took on each tick
    * every opponent style change, and how many ticks passed before our own
      overhead matched that style ("time to correct overhead")

  Time-to-correct-overhead is the metric that matters. The flag skips a 2-tick
  weapon-stability wait when the opponent's armour also changed, so the A/B is:

      & scripts/analyze-ticks.ps1 -Path run-flag-OFF.tsv
      & scripts/analyze-ticks.ps1 -Path run-flag-ON.tsv

  and compare the median. Anything else (branch counts alone) says the flag
  fired, not that it helped.

  Column positions are resolved by name from the header, so adding recorder
  columns will not silently shift the numbers.

.EXAMPLE
  & scripts/analyze-ticks.ps1                       # newest recording in %APPDATA%
  & scripts/analyze-ticks.ps1 -Path .\session.tsv
#>
param(
    [string] $Path,
    [switch] $TopBranches
)

$ErrorActionPreference = 'Stop'

# A real median: average the two middle values on an even count, so a report
# that says "median 1.5 ticks" is not secretly reporting the upper middle.
# NOTE the $null test, not `-not $values`: a one-element array holding 0 coerces
# to $false, which would silently blank the median on a one-switch recording.
function Get-Median([int[]] $values) {
    if ($null -eq $values -or $values.Count -eq 0) { return $null }
    $s = @($values | Sort-Object)
    if ($s.Count % 2 -eq 1) { return $s[[int][math]::Floor($s.Count / 2)] }
    return ($s[$s.Count / 2 - 1] + $s[$s.Count / 2]) / 2.0
}

function Get-NewestRecording {
    $dir = Join-Path $env:APPDATA 'Roatz\ticks'
    if (-not (Test-Path -LiteralPath $dir)) { return $null }
    Get-ChildItem -LiteralPath $dir -Filter '*.tsv' -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

if (-not $Path) {
    $Path = Get-NewestRecording
    if (-not $Path) {
        Write-Host 'No -Path given and no recordings found under %APPDATA%\Roatz\ticks.' -ForegroundColor Red
        Write-Host 'Record one with:  Roatz.exe -Droatz.rec=true' -ForegroundColor Yellow
        exit 1
    }
    Write-Host ("Using newest recording: " + $Path) -ForegroundColor DarkGray
}
if (-not (Test-Path -LiteralPath $Path)) {
    Write-Host ("No such file: " + $Path) -ForegroundColor Red
    exit 1
}

# The recorder writes '#' comment lines (banner, dropped-row footer) around a
# single header line. Strip them or ConvertFrom-Csv treats the banner as headers.
$lines = Get-Content -LiteralPath $Path | Where-Object { $_ -and -not $_.StartsWith('#') }
if ($lines.Count -lt 2) {
    Write-Host 'Recording has no data rows.' -ForegroundColor Yellow
    exit 0
}
$rows = $lines | ConvertFrom-Csv -Delimiter "`t"

$cols = ($lines[0] -split "`t")
function Require-Column([string] $name) {
    if ($cols -notcontains $name) {
        Write-Host ("Recording is missing the '" + $name + "' column - re-record with a current agent.") -ForegroundColor Red
        exit 1
    }
}
foreach ($c in 'tick', 'owpn', 'ostyle', 'oh', 'defpray') { Require-Column $c }

Write-Host ''
Write-Host ("Rows: " + $rows.Count + "   Columns: " + $cols.Count) -ForegroundColor Cyan

# ── Branch distribution ──────────────────────────────────────────────────────
$branchCounts = @{}
$weaponSwitches = 0
$styleChanges = 0
$deltas = New-Object System.Collections.Generic.List[object]
$missed = New-Object System.Collections.Generic.List[object]

$prevWpn = $null
$prevStyle = $null
$pending = $null

foreach ($r in $rows) {
    $b = $r.defpray
    if (-not $branchCounts.ContainsKey($b)) { $branchCounts[$b] = 0 }
    $branchCounts[$b]++

    # Opponent weapon change. 0 / blank means "slot unreadable mid-swap".
    if ($r.owpn -and $r.owpn -ne '0' -and $prevWpn -and $r.owpn -ne $prevWpn) { $weaponSwitches++ }
    if ($r.owpn -and $r.owpn -ne '0') { $prevWpn = $r.owpn }

    # A style change is what the overhead actually keys off, so that is the
    # trigger we time from. The first row is not a change ($prevStyle is null).
    if ($null -ne $prevStyle -and $r.ostyle -ne $prevStyle) {
        # A pending measurement the opponent has moved on from was never
        # satisfied - report it rather than letting it vanish.
        if ($pending) { $missed.Add($pending) }
        if ($r.ostyle -in @('MELEE', 'RANGED', 'MAGIC')) {
            $styleChanges++
            $pending = [pscustomobject]@{ Style = $r.ostyle; Tick = [int]$r.tick; Branch = $r.defpray }
        } else {
            $pending = $null   # UNKNOWN: mid-swap, nothing to react to
        }
    }

    if ($pending -and $r.oh -eq $pending.Style) {
        $deltas.Add([pscustomobject]@{
            Ticks  = [int]$r.tick - $pending.Tick
            Style  = $pending.Style
            Branch = $pending.Branch
        })
        $pending = $null
    }
    $prevStyle = $r.ostyle
}
if ($pending) { $missed.Add($pending) }

Write-Host ''
Write-Host 'Defensive-prayer branch (per tick)' -ForegroundColor Cyan
$branchCounts.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object {
    $pct = if ($rows.Count) { 100.0 * $_.Value / $rows.Count } else { 0 }
    Write-Host ('  {0,-14} {1,7}  {2,5:N1}%' -f $_.Key, $_.Value, $pct)
}

Write-Host ''
Write-Host ('Opponent weapon changes : ' + $weaponSwitches)
Write-Host ('Opponent style changes  : ' + $styleChanges)

# ── The headline number ──────────────────────────────────────────────────────
Write-Host ''
Write-Host 'Time to correct overhead (ticks from their style change to our overhead matching)' -ForegroundColor Cyan
if ($deltas.Count -eq 0) {
    Write-Host '  no completed measurements' -ForegroundColor Yellow
} else {
    $sorted = @($deltas | Sort-Object Ticks | ForEach-Object { $_.Ticks })
    $median = Get-Median $sorted
    $p90    = $sorted[[int][math]::Min($sorted.Count - 1, [math]::Floor($sorted.Count * 0.9))]
    $immediate = @($deltas | Where-Object { $_.Ticks -le 1 }).Count
    Write-Host ('  measured   : ' + $deltas.Count + ' of ' + $styleChanges + ' style changes')
    Write-Host ('  median     : ' + $median + ' tick(s)')
    Write-Host ('  p90        : ' + $p90 + ' tick(s)')
    Write-Host ('  <=1 tick   : ' + $immediate + ' (' + [math]::Round(100.0 * $immediate / $deltas.Count) + '%)')
    Write-Host ''
    Write-Host '  by branch taken at the switch tick:' -ForegroundColor DarkGray
    $deltas | Group-Object Branch | Sort-Object Count -Descending | ForEach-Object {
        $m = Get-Median @($_.Group | ForEach-Object { $_.Ticks })
        Write-Host ('    {0,-14} n={1,-5} median={2} tick(s)' -f $_.Name, $_.Count, $m)
    }
}
if ($missed.Count -gt 0) {
    Write-Host ''
    Write-Host ('  not matched inside the recording: ' + $missed.Count) -ForegroundColor Yellow
    $missed | Group-Object Style | Sort-Object Count -Descending | ForEach-Object {
        Write-Host ('    {0,-8} {1}' -f $_.Name, $_.Count) -ForegroundColor DarkGray
    }
}

if ($TopBranches) {
    Write-Host ''
    Write-Host 'Branch at opponent style-change ticks' -ForegroundColor Cyan
    $deltas | Group-Object Branch | Sort-Object Count -Descending | ForEach-Object {
        Write-Host ('  {0,-14} {1}' -f $_.Name, $_.Count)
    }
}
