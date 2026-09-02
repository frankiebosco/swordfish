@echo off
REM ---------------------------------------------------------------
REM  Pull OBD probe logs off the phone.
REM
REM  The probe writes one NDJSON file per run to the app's external
REM  files directory. That location is deliberate: it needs no storage
REM  permission, and it is readable by adb without root.
REM
REM  Files land in tools\probe-logs\ alongside this script.
REM
REM  Each line is a self-describing JSON object. Useful filters once
REM  you have them (git bash, or any JSON tool):
REM
REM    grep '"kind":"step"'  probe-*.ndjson   what passed and failed
REM    grep '"kind":"rate"'  probe-*.ndjson   the throughput headline
REM    grep '"kind":"cmd"'   probe-*.ndjson   every command and reply
REM    grep '"kind":"sweep"' probe-*.ndjson   supported-PID enumeration
REM
REM  The cmd records are the ones worth keeping: they are real ND2
REM  reply strings, and the frame parser in ObdPid is currently tested
REM  only against synthetic ones.
REM ---------------------------------------------------------------
setlocal
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
set REMOTE=/sdcard/Android/data/dev.swordfish/files
set LOCAL=%~dp0probe-logs

echo Pulling probe logs AND drive recordings from %REMOTE%
if not exist "%LOCAL%" mkdir "%LOCAL%"

"%ADB%" pull "%REMOTE%" "%LOCAL%"
if errorlevel 1 (
  echo.
  echo PULL FAILED.
  echo   - is the phone connected and authorised?  adb devices
  echo   - has the probe been run at least once?   the directory
  echo     does not exist until the first run creates it.
  pause
  exit /b 1
)

echo.
echo ============================================================
echo  Logs are in %LOCAL%
echo ============================================================
echo.
dir /b "%LOCAL%"
pause
