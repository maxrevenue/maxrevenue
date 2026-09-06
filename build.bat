@echo off
setlocal

REM ===================================================
REM  Font Manager Build Script
REM  Output: build\fontmanager-windows.jar
REM ===================================================

set "JDK="
if exist "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot" (
  set "JDK=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
) else if defined JAVA_HOME (
  set "JDK=%JAVA_HOME%"
)

if "%JDK%"=="" (
  set "JAVAC=javac.exe"
  set "JAR=jar.exe"
) else (
  set "JAVAC=%JDK%\bin\javac.exe"
  set "JAR=%JDK%\bin\jar.exe"
)

set "SRC=src"
set "OUT=build\classes"
set "LIB=build\fontmanager-windows.jar"
set "ATTACH_OUT=build\attach"

echo.
echo ==========================================
echo   Font Manager Build
echo ==========================================

:: Clean
if exist build\classes rmdir /s /q build\classes
if not exist "%ATTACH_OUT%" mkdir "%ATTACH_OUT%"

:: Compile attach helper (used by attach-agent.ps1)
echo [0/2] Compiling AttachLoader...
"%JAVAC%" -encoding UTF-8 -d "%ATTACH_OUT%" tools\AttachLoader.java
if %ERRORLEVEL% NEQ 0 (
  echo [ERROR] AttachLoader compile failed!
  pause
  exit /b 1
)
echo   AttachLoader OK

mkdir build\classes

:: Compile
echo [1/2] Compiling...
"%JAVAC%" --release 11 -d "%OUT%" ^
  %SRC%\com\sun\java\fontmgr\Stealth.java ^
  %SRC%\com\sun\java\fontmgr\AnimationMonitor.java ^
  %SRC%\com\sun\java\fontmgr\GearSwapEngine.java ^
  %SRC%\com\sun\java\fontmgr\DharokController.java ^
  %SRC%\com\sun\java\fontmgr\EatPunishController.java ^
  %SRC%\com\sun\java\fontmgr\TickListener.java ^
  %SRC%\com\sun\java\fontmgr\SharedMemory.java ^
  %SRC%\com\sun\java\fontmgr\GameState.java ^
  %SRC%\com\sun\java\fontmgr\StateReader.java ^
  %SRC%\com\sun\java\fontmgr\TickEngine.java ^
  %SRC%\com\sun\java\fontmgr\InventoryTracker.java ^
  %SRC%\com\sun\java\fontmgr\NhLoadout.java ^
  %SRC%\com\sun\java\fontmgr\AnimationDb.java ^
  %SRC%\com\sun\java\fontmgr\MaxHitCalculator.java ^
  %SRC%\com\sun\java\fontmgr\Humanizer.java ^
  %SRC%\com\sun\java\fontmgr\PauseManager.java ^
  %SRC%\com\sun\java\fontmgr\ClientThreadGuard.java ^
  %SRC%\com\sun\java\fontmgr\RtLookup.java ^
  %SRC%\com\sun\java\fontmgr\CombatScript.java ^
  %SRC%\com\sun\java\fontmgr\ClassFilePatcher.java ^
  %SRC%\com\sun\java\fontmgr\HardcodedCombatAgent.java ^
  %SRC%\com\sun\java\fontmgr\swap\Swap.java ^
  %SRC%\com\sun\java\fontmgr\swap\CommandParser.java ^
  %SRC%\com\sun\java\fontmgr\swap\SwapManager.java ^
  %SRC%\com\sun\java\fontmgr\swap\CommandExecutor.java ^
  %SRC%\com\sun\java\fontmgr\swap\SwapDispatcher.java ^
  %SRC%\com\sun\java\fontmgr\swap\SwapperPanel.java ^
  %SRC%\com\sun\java\fontmgr\MiniOverlayUI.java ^
  %SRC%\com\sun\java\fontmgr\UiExecutor.java ^
  %SRC%\com\sun\java\fontmgr\HotkeyManager.java ^
  %SRC%\com\sun\java\fontmgr\OverlayUI.java ^
  %SRC%\com\sun\java\fontmgr\FontManager.java

if %ERRORLEVEL% NEQ 0 (
  echo [ERROR] Compile failed!
  pause
  exit /b 1
)
echo   Compile OK

:: Package
echo [2/2] Packaging...
"%JAR%" cfm "%LIB%" manifest.mf -C "%OUT%" .

if %ERRORLEVEL% NEQ 0 (
  echo [ERROR] JAR failed!
  pause
  exit /b 1
)

for %%F in ("%LIB%") do set SIZE=%%~zF
set /a SIZEKB=%SIZE% / 1024
echo   JAR OK: %LIB% (%SIZEKB% KB)

echo ==========================================
echo   BUILD COMPLETE
echo ==========================================
echo.
