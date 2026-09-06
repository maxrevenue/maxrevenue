@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "JDK=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
if not exist "%JDK%\bin\java.exe" set "JDK=%JAVA_HOME%"
if not exist "%JDK%\bin\java.exe" (
  echo ERROR: JDK not found.
  exit /b 1
)

set "PID=%~1"
if "%PID%"=="" (
  echo Usage: attach-agent.cmd ^<pid^>
  exit /b 1
)

set "SRC=%CD%\build\fontmanager-windows.jar"
set "CACHE=%TEMP%\.cache"
set "AGENT=%CACHE%\fontconfig-ext.jar"
set "CP=%CD%\build\attach"
if not exist "%CP%\AttachLoader.class" (
  echo ERROR: Run gradlew.bat buildAll first.
  exit /b 1
)
if not exist "%SRC%" (
  echo ERROR: Build artifact missing.
  exit /b 1
)

if not exist "%CACHE%" mkdir "%CACHE%"
copy /Y "%SRC%" "%AGENT%" >nul

tasklist /FI "PID eq %PID%" 2>nul | find /I "%PID%" >nul
if errorlevel 1 (
  echo ERROR: PID %PID% not running.
  exit /b 2
)

"%JDK%\bin\java.exe" -Dfontmgr.attach.verbose=false --add-modules jdk.attach -cp "%CP%" AttachLoader %PID% "%AGENT%"
set "RC=!ERRORLEVEL!"
if not "!RC!"=="0" (
  "%JDK%\bin\java.exe" -Dfontmgr.attach.verbose=false -cp "%CP%" AttachLoader %PID% "%AGENT%"
  set "RC=!ERRORLEVEL!"
)

if "!RC!"=="0" exit /b 0
if "!RC!"=="4" exit /b 0
exit /b !RC!
