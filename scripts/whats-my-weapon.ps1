#Requires -Version 5.1
<#
.SYNOPSIS
  Decode the live agent state and tell you exactly which item ids you have equipped.

.DESCRIPTION
  The agent publishes a 128-byte snapshot per tick to
  %TEMP%\.cache\fontconfig-ext.dat (see GameState.java, wire v2). Slot 3 is the
  weapon. This prints the ids and flags whether the weapon is a Blue moon spear,
  which is the item that left-click Ice and the LCC path key off.

  It checks LIVENESS first. If the state file has not been written in the last few
  seconds the agent is not ticking, and every other value shown is stale — which is
  the usual explanation for "I applied the fix and nothing changed".

  Run it while logged in with the weapon you care about equipped.

.EXAMPLE
  & scripts\whats-my-weapon.ps1
  & scripts\whats-my-weapon.ps1 -Watch      # refresh until Ctrl+C
#>
param([switch] $Watch)

$ErrorActionPreference = 'Stop'

$path = Join-Path $env:TEMP '.cache\fontconfig-ext.dat'
if (-not (Test-Path -LiteralPath $path)) {
    Write-Host "No shared-memory file at $path" -ForegroundColor Red
    Write-Host 'The agent is not attached, or the client has not ticked yet.' -ForegroundColor Yellow
    exit 1
}

$SLOT = @{
    0 = 'helm'; 1 = 'cape'; 2 = 'amulet'; 3 = 'WEAPON'; 4 = 'body'; 5 = 'shield'
    7 = 'legs'; 9 = 'gloves'; 10 = 'boots'; 12 = 'ring'; 13 = 'ammo'
}
# Mirrors InventoryTracker
$BLUE_MOON = @(28988, 29849)

function Show-State {
    # The agent holds this file open, so a plain ReadAllBytes throws "used by another
    # process". Open it with FileShare.ReadWrite instead — that also means a
    # successful read proves a live agent is attached.
    $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open,
                                [System.IO.FileAccess]::Read,
                                [System.IO.FileShare]::ReadWrite)
    try {
        $b = New-Object byte[] 160
        $n = $fs.Read($b, 0, 160)
    } finally { $fs.Close() }
    if ($n -lt 128) { Write-Host 'Snapshot shorter than 128 bytes.' -ForegroundColor Yellow; return }

    # The writer uses a seqlock (SharedMemory.publish): it writes an odd sequence
    # word, writes the payload, then writes the next even word. An ODD word means a
    # publish died half way, and an unchanged word means the agent is not publishing.
    # Reading the payload without checking this is how a stale frame from an earlier
    # session gets reported as live data — which looks exactly like "logged out".
    $seq = [BitConverter]::ToInt32($b, 128)
    if ($seq % 2 -ne 0) {
        Write-Host ''
        Write-Host ("SEQLOCK IS ODD (seq={0}) - a publish died half way." -f $seq) -ForegroundColor Red
        Write-Host 'The payload below is a HALF-WRITTEN or STALE frame. Do not trust it.'
        Write-Host 'Run with -Dagent.filelog=true; CombatScript now logs the first failure as'
        Write-Host '"shared-memory publish failed (once): <exception>".' -ForegroundColor Yellow
        Write-Host ''
    }

    # Liveness first: if the agent is not ticking, every other reading below is
    # stale and any conclusion drawn from it is wrong.
    $age = (Get-Date) - (Get-Item -LiteralPath $path).LastWriteTime
    $ticking = $age.TotalSeconds -le 3
    if (-not $ticking) {
        Write-Host ("AGENT IS NOT TICKING - last state write {0:N1}s ago (seq={1})" -f $age.TotalSeconds, $seq) -ForegroundColor Red
        Write-Host 'Nothing below is live. The values are whatever was left in the file,' -ForegroundColor Yellow
        Write-Host 'which is why they look identical every time (tick=7, hp=42, no gear).'
        Write-Host ''
    } else {
        Write-Host ('agent LIVE (last write {0:N1}s ago, seq={1})' -f $age.TotalSeconds, $seq) -ForegroundColor Green
    }

    $version = [BitConverter]::ToInt32($b, 90)
    $tick    = [BitConverter]::ToInt32($b, 0)
    $hp      = [BitConverter]::ToInt16($b, 4)
    $prayer  = [BitConverter]::ToInt16($b, 6)
    $spec    = [BitConverter]::ToInt16($b, 8)
    $energy  = [BitConverter]::ToInt16($b, 10)
    $anim    = [BitConverter]::ToInt16($b, 12)
    $px      = [BitConverter]::ToInt16($b, 14)
    $py      = [BitConverter]::ToInt16($b, 16)
    $tlen    = $b[20]
    $target  = [System.Text.Encoding]::UTF8.GetString($b, 21, $tlen)
    $pflags  = [BitConverter]::ToInt16($b, 100)

    Write-Host ("wire v{0}  tick={1}  hp={2}  prayer={3}  spec={4}  energy={5}  anim={6}  pos=({7},{8})" -f `
        $version, $tick, $hp, $prayer, $spec, $energy, $anim, $px, $py)
    Write-Host ("target='{0}'  prayerFlags=0x{1:X2}" -f $target, $pflags)

    if ($version -ne 2) { Write-Host 'Unexpected wire version - decoder may be stale.' -ForegroundColor Yellow }

    Write-Host ''
    Write-Host 'equipped:'
    $weapon = 0
    $anyEquip = $false
    for ($i = 0; $i -lt 14; $i++) {
        $id = [BitConverter]::ToInt16($b, 62 + ($i * 2))
        if ($i -eq 3) { $weapon = $id }
        if ($id -ne 0) {
            $anyEquip = $true
            $mark = if ($i -eq 3) { ' <-- weapon' } else { '' }
            Write-Host ("  slot {0,2} {1,-7} = {2}{3}" -f $i, $SLOT[$i], $id, $mark)
        }
    }

    # Ticking is necessary but NOT sufficient. Our tick engine runs off a timer, so
    # it keeps ticking at the login screen, where the client's own tick stays near
    # zero and nothing is readable. Distinguish that from a genuinely broken reader.
    if ($ticking -and -not $anyEquip -and $target -eq '' -and $anim -le 0) {
        Write-Host ''
        Write-Host 'AGENT TICKING BUT READING NO GAME STATE' -ForegroundColor Red
        Write-Host 'No equipment, no target, no animation, and the client tick is' -ForegroundColor Yellow
        Write-Host ("{0} -- a logged-in client is well past that." -f $tick)
        Write-Host 'This is the login screen, or a client that never entered the world.'
        Write-Host 'Log in fully, then run this again. If you ARE in-world holding a weapon'
        Write-Host 'and this still shows nothing, the state reader is broken -- that is the'
        Write-Host 'real bug, and it explains everything else being broken too.' -ForegroundColor Yellow
        return
    }

    Write-Host ''
    if ($weapon -eq 0) {
        Write-Host 'Weapon slot is EMPTY - equip the item and re-run.' -ForegroundColor Yellow
        Write-Host '(An empty weapon slot also makes the agent report style UNKNOWN, and'
        Write-Host ' makes LeftClickCast clear your selected spell on every click.)' -ForegroundColor DarkGray
    } else {
        Write-Host ("WEAPON ID = {0}" -f $weapon) -ForegroundColor Cyan
        if ($BLUE_MOON -contains $weapon) {
            Write-Host '  -> matches a known Blue moon spear id; LCC should recognise it.' -ForegroundColor Green
        } else {
            Write-Host '  -> NOT a known Blue moon spear id.' -ForegroundColor Yellow
            Write-Host ("     Known ids: {0}" -f ($BLUE_MOON -join ', '))
            Write-Host '     Either add this id to BLUE_MOON_SPEAR_IDS, or use the pin button'
            Write-Host '     on the PR branch so the id is learned instead of hardcoded.'
        }
    }
}

do {
    Clear-Host
    Show-State
    if ($Watch) { Start-Sleep -Seconds 2 } 
} while ($Watch)
