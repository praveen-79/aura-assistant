@echo off
echo ============================================================================
echo   AURA - 1-Click USB APK Installer for Android Phones
echo ============================================================================
cd /d "%~dp0"

set "ADB_CMD=adb"
if exist "C:\Program Files (x86)\pcsuite\adb_41\adb.exe" (
    set "ADB_CMD=C:\Program Files (x86)\pcsuite\adb_41\adb.exe"
) else if exist "C:\Program Files (x86)\pcsuite\adb\adb.exe" (
    set "ADB_CMD=C:\Program Files (x86)\pcsuite\adb\adb.exe"
)

if not exist "AuraAssistant.apk" (
    echo [ERROR] AuraAssistant.apk not found! Run build_apk.bat first.
    pause
    exit /b 1
)

echo Checking connected Android devices via USB Debugging...
"%ADB_CMD%" devices
echo.
echo Installing AuraAssistant.apk onto connected phone...
"%ADB_CMD%" install -r "AuraAssistant.apk"
echo.
echo Launching Aura AI Assistant on your phone...
"%ADB_CMD%" shell am start -n com.aura.assistant/.MainActivity
pause
