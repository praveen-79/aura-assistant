@echo off
echo ============================================================================
echo   AURA - Push Clean Repository to GitHub (Secrets Hidden & Protected)
echo ============================================================================
cd /d "%~dp0"

echo [1/3] Verifying .env and secrets are safely ignored...
git status --short

set /p REPO_URL="Enter your GitHub Repository URL (e.g. https://github.com/praveen-79/aura-assistant.git): "
if "%REPO_URL%"=="" (
    echo [ERROR] No URL provided. Aborting.
    pause
    exit /b 1
)

echo.
echo [2/3] Setting remote origin to %REPO_URL%...
git remote remove origin 2>nul
git remote add origin %REPO_URL%
git branch -M main

echo.
echo [3/3] Pushing to GitHub main branch...
git push -u origin main
echo.
echo [DONE] Repository pushed to GitHub!
echo.
echo ============================================================================
echo   ENABLE GITHUB PAGES FOR YOUR DOWNLOAD WEBSITE:
echo   1. Go to your repo on GitHub: %REPO_URL%
echo   2. Click Settings -^> Pages
echo   3. Under "Build and deployment", set Branch to 'main' and Folder to '/ (root)'
echo   4. Click Save.
echo   Your download website will be live in 1-2 minutes!
echo ============================================================================
pause
