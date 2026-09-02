@echo off
REM ---------------------------------------------------------------
REM  Capture the Android Auto HOST log after a head-unit session.
REM
REM  Run this the moment a session ends -- BEFORE unplugging and
REM  before starting another one. The main ring buffer is 16 MiB and
REM  a projecting session fills it in minutes, so evidence of a crash
REM  is routinely lost simply by waiting.
REM
REM  Files land in tools\probe-logs\host\ with a timestamp.
REM
REM  Three files come out:
REM
REM    logcat-crash-*.txt  the crash buffer -- the FATAL EXCEPTION and
REM                        which PROCESS died. Read this one first: if
REM                        it says gearhead:projection, the host
REM                        crashed and Swordfish was collateral.
REM
REM    logcat-gh-*.txt     gearhead's own decisions, filtered. This is
REM                        where launcher/validation answers live.
REM
REM    logcat-full-*.txt   everything, as a fallback.
REM
REM  Requires the verbose tags to have been set (see below) -- gearhead
REM  logs almost nothing without them, and setprop does NOT survive a
REM  phone reboot, so re-run tools\gh-verbose.bat after any restart.
REM ---------------------------------------------------------------
setlocal
set OUT=%~dp0probe-logs\host
if not exist "%OUT%" mkdir "%OUT%"

REM Locale-independent timestamp. Parsing %date% depends on regional
REM settings and produced "22Sat08-2026" here; wmic always yields
REM YYYYMMDDHHMMSS so the files sort chronologically.
for /f %%i in ('wmic os get LocalDateTime ^| findstr /r "^[0-9]"') do set DT=%%i
set TS=%DT:~0,8%-%DT:~8,6%

echo Capturing host logs to %OUT% ...

adb logcat -b crash -d > "%OUT%\logcat-crash-%TS%.txt" 2>nul
adb logcat -d > "%OUT%\logcat-full-%TS%.txt" 2>nul

REM The signal lines, pulled out so they do not have to be hunted for.
REM
REM  "declared as a navigation app"     -> the host accepted our service
REM  "Package DENIED"                   -> the Play-backed validator rejected someone
REM  "availableApps"                    -> the list we need to be IN to get a tile
REM  "clearing invalid component"       -> the saved default being erased
REM  "Navigation intent processor"      -> present on a launch that WORKS;
REM                                        absent on one that crashes
REM  "Scheduling restart of crashed"    -> which service actually died
findstr /I /C:"swordfish" /C:"DefaultAppManager" /C:"VALIDATOR" /C:"availableApps" ^
    /C:"IntentProcessor" /C:"navigation app" /C:"Scheduling restart of crashed" ^
    /C:"NavClientManager" /C:"AppIconFactory" ^
    "%OUT%\logcat-full-%TS%.txt" > "%OUT%\logcat-gh-%TS%.txt" 2>nul

echo.
echo   crash : %OUT%\logcat-crash-%TS%.txt
echo   gh    : %OUT%\logcat-gh-%TS%.txt
echo   full  : %OUT%\logcat-full-%TS%.txt
echo.

REM Show the headline immediately -- usually the whole answer.
echo --- FATAL EXCEPTIONS (if any) ---
findstr /I /C:"FATAL EXCEPTION" /C:"Process:" "%OUT%\logcat-crash-%TS%.txt" 2>nul
echo --- END ---
endlocal
