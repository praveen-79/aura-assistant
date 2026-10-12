@echo off
echo ============================================================================
echo   AURA - Configure Gemini API Key
echo ============================================================================
echo.
echo Paste your Google Gemini API Key below (starts with AIzaSy...):
echo (Get one free at https://aistudio.google.com/app/apikey)
echo.
set /p APIKEY="API Key: "
if not "%APIKEY%"=="" (
    echo GEMINI_API_KEY=%APIKEY%> "%~dp0.env"
    echo.
    echo ============================================================================
    echo [SUCCESS] API Key saved to .env!
    echo It is listed in .gitignore so it will NEVER be uploaded to GitHub.
    echo Aura will now automatically activate Gemini 3.8 Flash and Live Audio!
    echo ============================================================================
) else (
    echo [CANCELLED] No key entered.
)
echo.
pause
