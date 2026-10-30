@echo off
REM ============================================================
REM Veil one-click installer (with TCL package-verifier bypass)
REM Double-click to run, or:  install-veil.bat [TV_IP] [APK_PATH]
REM Requires adb (Android platform-tools) on PATH, or placed next to this script.
REM ============================================================
setlocal
set "TV_IP=%~1"
if "%TV_IP%"=="" set "TV_IP=192.168.31.205"
set "APK=%~2"

REM --- locate adb ---
set "ADB=adb"
if exist "%~dp0adb.exe" set "ADB=%~dp0adb.exe"
if exist "%~dp0platform-tools\adb.exe" set "ADB=%~dp0platform-tools\adb.exe"
if exist "%~dp0..\android-sdk\platform-tools\adb.exe" set "ADB=%~dp0..\android-sdk\platform-tools\adb.exe"

REM --- locate the APK (handed-over file first, then a local gradle build) ---
if "%APK%"=="" (
  for %%f in ("%~dp0Veil-TV-*-full*.apk") do set "APK=%%f"
)
if "%APK%"=="" (
  if exist "%~dp0..\app\build\outputs\apk\full\release\Veil-TV-full-release.apk" (
    set "APK=%~dp0..\app\build\outputs\apk\full\release\Veil-TV-full-release.apk"
  )
)
if "%APK%"=="" (
  echo [ERROR] No APK found. Put a Veil-TV-*-full.apk next to this script, or pass its path as argument 2.
  goto :done
)

echo [1/5] Connect to TV %TV_IP%:5555
%ADB% connect %TV_IP%:5555
%ADB% -s %TV_IP%:5555 wait-for-device

echo [2/5] Temporarily disable TCL package verifier (bypass INSTALL_FAILED_VERIFICATION_FAILURE)
%ADB% -s %TV_IP%:5555 shell pm disable-user --user 0 com.android.packageinstaller

echo [3/5] Install Veil (replace existing)
%ADB% -s %TV_IP%:5555 install -r "%APK%"
if errorlevel 1 (
  echo [WARN] install returned non-zero - check the log above
)

echo [4/5] Re-enable TCL package verifier (restore it for security)
%ADB% -s %TV_IP%:5555 shell pm enable com.android.packageinstaller

echo [5/5] Set Veil as the default home screen (safe to ignore if it fails)
%ADB% -s %TV_IP%:5555 shell cmd package set-home-activity com.veil.launcher/.MainActivity

echo.
echo Done. If Veil did not open by itself, press the Home button or pick it in Settings.
echo Tip: for Veil to resist being kicked off by the stock launcher, enable its accessibility
echo      "launcher guard" service in Settings once it is installed.
:done
pause
