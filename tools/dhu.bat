@echo off
REM ---------------------------------------------------------------
REM  Launch the Android Auto Desktop Head Unit.
REM
REM  The DHU is an INTERACTIVE console app: it opens a ">" prompt and
REM  exits immediately if stdin is not a real terminal. It therefore
REM  cannot be launched from a background script -- it must run in a
REM  console window you can type into.
REM
REM  Prerequisites on the phone:
REM    1. USB debugging on, phone connected
REM    2. Android Auto -> Settings -> About -> tap Version 10x
REM    3. Android Auto -> 3-dot menu -> "Start head unit server"
REM    4. Settings -> Previously connected cars ->
REM         "Add new cars to Android Auto" enabled
REM ---------------------------------------------------------------

echo Forwarding port 5277...
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" forward tcp:5277 tcp:5277
if errorlevel 1 (
  echo.
  echo FAILED to forward. Is the phone connected and authorised?
  echo Run: adb devices
  pause
  exit /b 1
)

echo Starting Desktop Head Unit...
echo.
cd /d "%LOCALAPPDATA%\Android\Sdk\extras\google\auto"
desktop-head-unit.exe

echo.
echo DHU exited. If it closed instantly, the head unit server on the
echo phone is probably not running -- see prerequisites above.
pause
