@echo off
title Roat PKz Fresh Client Install
cd /d "%~dp0"

echo ==========================================
echo   Roat PKz - Force Fresh Client Download
echo ==========================================
echo.
echo CLOSE the Roat game window before continuing.
echo.
pause

set "RPKZ=%USERPROFILE%\rpkzclient"
set "BACKUP=%USERPROFILE%\Desktop\roat-accounts-backup.dat"
set "ROAT_EXE=C:\Program Files (x86)\roatpkz_runelite\Roat Pkz.exe"

echo [1/5] Stopping ALL Roat/Java processes...
taskkill /F /IM "Roat Pkz.exe" >nul 2>&1
taskkill /F /IM java.exe >nul 2>&1
taskkill /F /IM javaw.exe >nul 2>&1
timeout /t 5 /nobreak >nul
taskkill /F /IM java.exe >nul 2>&1
taskkill /F /IM javaw.exe >nul 2>&1
timeout /t 3 /nobreak >nul

echo [2/5] Backing up accounts.dat...
if exist "%RPKZ%\accounts.dat" (
    copy /Y "%RPKZ%\accounts.dat" "%BACKUP%" >nul
    echo   Saved to %BACKUP%
) else if exist "%BACKUP%" (
    echo   Using existing backup on Desktop
) else (
    echo   No accounts to backup
)

echo [3/5] Removing stale client cache...
if exist "%RPKZ%" (
    rmdir /s /q "%RPKZ%" 2>nul
    timeout /t 2 /nobreak >nul
)
if exist "%RPKZ%" (
    echo   Some files still locked - renaming folder instead...
    set "STALE=%RPKZ%_OLD_%RANDOM%"
    ren "%RPKZ%" "%STALE%" 2>nul
    if exist "%RPKZ%" (
        echo   ERROR: Could not remove or rename %RPKZ%
        echo   Close ALL Roat windows and Task Manager java.exe, then re-run.
        pause
        exit /b 1
    )
    echo   Old folder moved aside. Delete it later from:
    echo   %STALE%
)

if exist "%RPKZ%" (
    echo   ERROR: %RPKZ% still exists
    pause
    exit /b 1
)
echo   Cache cleared OK

echo [4/5] Waiting 10 seconds before launch...
timeout /t 10 /nobreak >nul

echo [5/5] Starting Roat PKz (fresh download)...
if exist "%ROAT_EXE%" (
    start "" "%ROAT_EXE%"
    echo.
    echo ==========================================
    echo   Roat PKz opened.
    echo.
    echo   WAIT 2-5 minutes for download to finish.
    echo   Do NOT click Login until the client is fully loaded.
    echo   Then log in ONCE and wait 60 seconds.
    echo.
    echo   If you see "Error connecting to server":
    echo     wait 30 seconds and click Continue ONCE.
    echo     If it fails again, wait 15 min - rate limited.
    echo.
    echo   When in-game, close client, then run:
    echo     .\launch.ps1 -Attach
    echo ==========================================
) else (
    echo   Roat Pkz.exe not found - opening installer download...
    start "" "https://downloadcache.roatpkz.com/runelite/RoatPkz64.exe"
    echo   Install it, then run this script again.
)
pause
