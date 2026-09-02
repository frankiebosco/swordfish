@echo off
REM ---------------------------------------------------------------
REM  Turn on Android Auto's verbose logging, and clear the buffer.
REM
REM  Gearhead logs almost NOTHING by default -- the launcher and
REM  validation decisions that explain why Swordfish does or does not
REM  get a tile are all behind these tags. Without them a session
REM  produces no usable evidence.
REM
REM  setprop does NOT survive a phone reboot. Re-run this after any
REM  restart, and before any session you intend to diagnose.
REM
REM  Clearing the buffer is part of the job, not a nicety: the main
REM  ring is 16 MiB and a projecting session fills it in minutes, so
REM  starting dirty means the interesting lines are already being
REM  overwritten. Capture anything you still need FIRST with
REM  tools\grab-host-log.bat -- this script discards the buffer.
REM ---------------------------------------------------------------
setlocal

echo Setting gearhead verbose tags ...
adb shell setprop log.tag.GH VERBOSE
adb shell setprop log.tag.CAR VERBOSE
adb shell setprop log.tag.CarApp VERBOSE

echo Growing the ring buffer to 16M ...
adb logcat -G 16M

echo Clearing buffers ...
adb logcat -c 2>nul
adb logcat -b crash -c 2>nul

echo.
echo Verifying:
for %%T in (GH CAR CarApp) do (
    echo|set /p="  log.tag.%%T = "
    adb shell getprop log.tag.%%T
)
echo.
echo Ready. Run the session, then: tools\grab-host-log.bat
endlocal
