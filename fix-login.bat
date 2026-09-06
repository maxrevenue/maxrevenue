@echo off
title Roat PKz Login Recovery
cd /d "%~dp0"

echo ==========================================
echo   Roat PKz Login Recovery
echo ==========================================
echo.
echo STOP: Do not paste anything into this window.
echo Just wait for it to finish.
echo.

echo [1/4] Closing Java clients...
taskkill /F /IM java.exe >nul 2>&1
taskkill /F /IM javaw.exe >nul 2>&1
timeout /t 3 /nobreak >nul

echo [2/4] Clearing cache...
if exist "%USERPROFILE%\.roatpkz\cache" rmdir /s /q "%USERPROFILE%\.roatpkz\cache"
if exist "%USERPROFILE%\.jagex_cache_32" rmdir /s /q "%USERPROFILE%\.jagex_cache_32"

echo [3/4] Waiting 90 seconds - DO NOT open Roat yet...
timeout /t 90 /nobreak

echo [4/4] Starting official Roat launcher...
set "JAVA=C:\Program Files (x86)\roatpkz_runelite\jre-64\bin\java.exe"
if not exist "%JAVA%" set "JAVA=C:\Program Files (x86)\roatpkz_runelite\jre\bin\java.exe"
set "LAUNCHER=%USERPROFILE%\rpkzclient\RoatPkzLauncher.jar"

if not exist "%LAUNCHER%" (
    echo ERROR: Launcher not found at %LAUNCHER%
    echo Download from https://roatpkz.com/download/
    pause
    exit /b 1
)

start "" "%JAVA%" -jar "%LAUNCHER%"

echo.
echo ==========================================
echo   Launcher opened.
echo   Log in ONCE and wait 60 seconds.
echo   Read the red text on the login screen.
echo ==========================================
pause
