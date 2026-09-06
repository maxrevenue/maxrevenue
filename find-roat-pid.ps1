# Find running Roat PKz java client PIDs
$all = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue)
$hits = @($all | Where-Object { $_.CommandLine -match 'roat|rpkz|RoatPkz|roat-rl' })

if ($hits.Count -gt 0) {
    Write-Host "Roat client(s):" -ForegroundColor Green
    $hits | ForEach-Object {
        $cli = [string]$_.CommandLine
        if ($cli.Length -gt 110) { $cli = $cli.Substring(0, 107) + '...' }
        Write-Host ("  PID {0}  {1}" -f $_.ProcessId, $cli)
    }
    Write-Host ""
    Write-Host "Attach:" -ForegroundColor Cyan
    Write-Host ("  .\attach-agent.cmd {0}" -f $hits[0].ProcessId)
    exit 0
}

Write-Host "No Roat client running." -ForegroundColor Yellow
Write-Host "  Start one:  .\launch.ps1 -Attach" -ForegroundColor Cyan
Write-Host ""

if ($all.Count -gt 0) {
    Write-Host "Other Java processes (not matched):" -ForegroundColor Gray
    foreach ($p in $all) {
        $cli = [string]$p.CommandLine
        if ($cli.Length -gt 110) { $cli = $cli.Substring(0, 107) + '...' }
        Write-Host ("  PID {0}  {1}" -f $p.ProcessId, $cli)
    }
} else {
    Write-Host "No java.exe processes at all." -ForegroundColor Gray
}

exit 1
