@echo off
REM ---------------------------------------------------------------
REM  The SLOW loop: build a signed AAB for the real head unit.
REM
REM  This is the only route to the car. Since the first Play install,
REM  Play App Signing re-signed dev.swordfish with its own key, so
REM  `adb install` of a local build fails with
REM  INSTALL_FAILED_UPDATE_INCOMPATIBLE. Uninstalling to force it would
REM  destroy installerPackageName=com.android.vending -- the Play
REM  attribution the launcher tile depends on, which cost $25 and
REM  several days of verification to obtain.
REM
REM  DO NOT UNINSTALL dev.swordfish. Ever. Not to test something, not
REM  to "start clean". Uninstalling means repeating the whole Play
REM  dance to get the tile back.
REM
REM  After this script:
REM    1. Play Console -> Testing -> Internal testing -> Create release
REM    2. upload the AAB it prints
REM    3. Review release -> Start rollout to Internal testing
REM    4. wait -- propagation has taken up to an hour, showing
REM       "Item not found" the whole time even though the console says
REM       the release is available. Waiting is the answer; changing
REM       settings during that window is not.
REM    5. phone: Play Store -> Swordfish -> Update
REM
REM  versionCode MUST increase for every upload. Play rejects a repeat.
REM ---------------------------------------------------------------
setlocal
cd /d "%~dp0.."

REM Surface the versionCode so a forgotten bump is caught before upload
REM rather than by a Play rejection several minutes later.
echo.
findstr /C:"versionCode" app\build.gradle.kts | findstr /R "[0-9]"
echo.
echo Is that versionCode HIGHER than the last upload? Ctrl+C now if not.
pause

echo Running the physics suite first...
call "%CD%\gradlew.bat" :physics:test --console=plain -q
if errorlevel 1 (
    echo.
    echo TESTS FAILED -- not building a shippable artifact.
    exit /b 1
)

echo Building signed AAB...
call "%CD%\gradlew.bat" :app:bundleRelease --console=plain -q
if errorlevel 1 (
    echo.
    echo BUILD FAILED.
    exit /b 1
)

echo.
echo   Upload this to Play Console -^> Internal testing:
echo   %CD%\app\build\outputs\bundle\release\app-release.aab
echo.
dir /b /a-d "app\build\outputs\bundle\release\app-release.aab" >nul 2>&1
if errorlevel 1 echo   WARNING: expected AAB not found.
endlocal
