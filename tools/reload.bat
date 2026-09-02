@echo off
REM ===============================================================
REM  SUPERSEDED 2026-08-23 -- use tools\dbg.bat instead.
REM
REM  This script predates the debug/release package split. It installs
REM  the debug APK (now dev.swordfish.debug) but force-stops
REM  dev.swordfish, which is the PLAY build -- the wrong app. Harmless,
REM  since a suffixed package cannot overwrite the Play one, but
REM  misleading: it looks like it reloaded something and did not.
REM
REM    tools\dbg.bat   fast DHU loop, debug build
REM    tools\ship.bat  signed AAB for the car, via Play
REM
REM  Kept only for the notes below on Android Auto's binding cache,
REM  which are still true and still worth reading.
REM ===============================================================
REM ---------------------------------------------------------------
REM  Rebuild, reinstall and restart Swordfish on the head unit.
REM
REM  IMPORTANT -- why a plain force-stop is not enough:
REM
REM  Android Auto rebinds the CarAppService IMMEDIATELY after it is
REM  killed, and the host keeps a cached binding to the OLD code. You
REM  see the process ID change while the display keeps showing the
REM  previous build. Confirmed the hard way: the APK installed, the
REM  package lastUpdateTime advanced, and the head unit still rendered
REM  the old string.
REM
REM  Reliable options, cheapest first:
REM
REM    A. Bump versionCode in app/build.gradle.kts before building.
REM       Android Auto notices a genuinely new version and reloads.
REM
REM    B. Stop Android Auto entirely (this script does it). That drops
REM       the DHU connection, so afterwards you must:
REM         1. phone: Android Auto -> 3-dot menu -> Start head unit server
REM         2. rerun tools\dhu.bat
REM ---------------------------------------------------------------
setlocal
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe

echo [1/4] Building...
call "%~dp0..\gradlew.bat" -p "%~dp0.." :app:assembleDebug -q
if errorlevel 1 (
  echo BUILD FAILED
  pause
  exit /b 1
)

echo [2/4] Installing...
"%ADB%" install -r "%~dp0..\app\build\outputs\apk\debug\app-debug.apk"
if errorlevel 1 (
  echo INSTALL FAILED - is the phone connected?
  pause
  exit /b 1
)

echo [3/4] Stopping Swordfish...
"%ADB%" shell am force-stop dev.swordfish

echo [4/4] Stopping Android Auto so it drops its cached binding...
"%ADB%" shell am force-stop com.google.android.projection.gearhead

echo.
echo ============================================================
echo  Build installed. The DHU connection has been dropped.
echo.
echo  NEXT STEPS:
echo    1. On the phone: Android Auto -^> 3-dot menu
echo                     -^> "Start head unit server"
echo    2. Run tools\dhu.bat
echo    3. Tap Swordfish on the head unit
echo ============================================================
echo.
pause
