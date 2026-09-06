param(
    [switch] $ResetCache,
    [switch] $LaunchOfficial
)

$RpkzDir = Join-Path $env:USERPROFILE "rpkzclient"
$RoatCache = Join-Path $env:USERPROFILE ".roatpkz\cache"
$LauncherJar = Join-Path $RpkzDir "RoatPkzLauncher.jar"
$GameJava = "C:\Program Files (x86)\roatpkz_runelite\jre-64\bin\java.exe"
if (-not (Test-Path -LiteralPath $GameJava)) {
    $GameJava = "C:\Program Files (x86)\roatpkz_runelite\jre\bin\java.exe"
}

Write-Host "Roat PKz Login Recovery" -ForegroundColor Cyan
Get-Process -Name java,javaw -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3

if ($ResetCache) {
    if (Test-Path -LiteralPath $RoatCache) {
        Remove-Item -LiteralPath $RoatCache -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "Waiting 90 seconds..." -ForegroundColor Yellow
Start-Sleep -Seconds 90

if ($LaunchOfficial -and (Test-Path -LiteralPath $LauncherJar)) {
    Start-Process -FilePath $GameJava -ArgumentList '-jar', $LauncherJar
}
