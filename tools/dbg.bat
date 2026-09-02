@echo off
REM ---------------------------------------------------------------
REM  The FAST loop: build and install the DEBUG build for the DHU.
REM
REM  Installs dev.swordfish.debug ("Swordfish DBG"), which is a
REM  SEPARATE APP from the Play-installed dev.swordfish. The Play build
REM  is never touched, so its com.android.vending attribution -- the
REM  thing the launcher tile depends on -- is never at risk.
REM
REM  USE THIS FOR VISUAL WORK ONLY.
REM
REM  The debug package has no Play ownership, so on the REAL head unit
REM  it will be denied exactly as the sideloaded build always was. The
REM  DHU performs no Play ownership check at all, which is the whole
REM  reason this works there. A layout that looks right on the DHU is
REM  NOT evidence it behaves on real hardware -- the DHU has already
REM  been proven to lie about the tile, validation and host crashes.
REM
REM  To ship to the car, see tools\ship.bat.
REM
REM  Sequence:
REM    1. phone: Android Auto -> 3-dot menu -> Start head unit server
REM    2. tools\dhu.bat        (forwards 5277, launches the DHU)
REM    3. tools\dbg.bat        (this -- rebuild and reinstall)
REM    4. tap "Swordfish DBG" on the DHU
REM
REM  Repeat 3-4 as often as you like; the DHU connection survives.
REM ---------------------------------------------------------------
setlocal
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
cd /d "%~dp0.."

echo Building debug...
call "%CD%\gradlew.bat" :app:assembleDebug --console=plain -q
if errorlevel 1 (
    echo.
    echo BUILD FAILED -- not installing.
    exit /b 1
)

echo Installing dev.swordfish.debug ...
"%ADB%" install -r app\build\outputs\apk\debug\app-debug.apk
if errorlevel 1 (
    echo.
    echo Install failed. If it mentions signatures, you may have an older
    echo debug build signed with a different key: uninstall ONLY the debug
    echo package and retry --  adb uninstall dev.swordfish.debug
    echo NEVER uninstall dev.swordfish itself; that destroys the Play
    echo attribution the tile depends on.
    exit /b 1
)

REM Android Auto caches its binding to the CarAppService and will keep
REM serving OLD code after a reinstall -- the process id changes while
REM the display stays stale. Force-stopping the debug package is enough
REM here because the host rebinds on next launch, and it does NOT drop
REM the DHU connection the way stopping Android Auto itself would.
"%ADB%" shell am force-stop dev.swordfish.debug

echo.
echo Installed. Tap "Swordfish DBG" on the DHU.
echo (If the display looks stale, bump versionCode and rerun.)
endlocal
