@echo off
REM ============================================================
REM keep-adb: keep the TV's network ADB connection alive.
REM Reconnects automatically when the link drops, so you can keep
REM pushing Veil updates without re-enabling wireless debugging.
REM Usage:  keep-adb.bat [TV_IP]   (default 192.168.31.205)
REM Press Ctrl+C to stop.
REM ============================================================
setlocal
set "TV_IP=%~1"
if "%TV_IP%"=="" set "TV_IP=192.168.31.205"
set "ADB=adb"
if exist "%~dp0adb.exe" set "ADB=%~dp0adb.exe"
if exist "%~dp0platform-tools\adb.exe" set "ADB=%~dp0platform-tools\adb.exe"
if exist "%~dp0..\android-sdk\platform-tools\adb.exe" set "ADB=%~dp0..\android-sdk\platform-tools\adb.exe"

echo keep-adb: watching %TV_IP%:5555, reconnect every 20s. Ctrl+C to quit.
:loop
%ADB% devices 2>nul | findstr /R "%TV_IP%:5555.*device" >nul
if errorlevel 1 (
  echo [%time%] offline - reconnecting...
  %ADB% connect %TV_IP%:5555
) else (
  echo [%time%] online
)
ping -n 21 127.0.0.1 >nul
goto :loop
