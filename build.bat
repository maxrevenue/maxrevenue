@echo off
setlocal
REM ===================================================
REM  Font Manager build — thin wrapper around Gradle.
REM
REM  This script used to carry its own javac file list,
REM  which drifted from build.gradle.kts (new sources
REM  silently never compiled). Gradle compiles by
REM  convention, so there is only one source of truth.
REM
REM  Output: build\fontmanager-windows.jar
REM          build\attach\AttachLoader.class
REM ===================================================

cd /d "%~dp0"

echo.
echo ==========================================
echo   Font Manager Build (gradlew buildAll)
echo ==========================================

call gradlew.bat --console=plain buildAll %*
if %ERRORLEVEL% NEQ 0 (
  echo [ERROR] Build failed!
  pause
  exit /b 1
)

set "LIB=build\fontmanager-windows.jar"
if not exist "%LIB%" (
  echo [ERROR] %LIB% was not produced.
  pause
  exit /b 1
)

echo ==========================================
for %%F in ("%LIB%") do echo   JAR OK: %LIB% (%%~zF bytes)
echo   BUILD COMPLETE
echo ==========================================
echo.
