@echo off
echo ============================================================================
echo   AURA - Building Signed Android APK (AuraAssistant.apk)
echo ============================================================================
cd /d "%~dp0"

:: Sync latest web UI into Android assets
copy /Y "static\index.html" "app\src\main\assets\index.html" >nul

if exist "%USERPROFILE%\.android\jdk17\jdk-17.0.20.1+1\bin\java.exe" (
    set "JAVA_HOME=%USERPROFILE%\.android\jdk17\jdk-17.0.20.1+1"
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)

call gradlew.bat assembleDebug
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    copy /Y "app\build\outputs\apk\debug\app-debug.apk" "AuraAssistant.apk" >nul
    echo.
    echo [SUCCESS] APK Built Successfully:
    echo   %CD%\AuraAssistant.apk
) else (
    echo.
    echo [ERROR] APK build failed. Check Gradle output above.
)
pause
