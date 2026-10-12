@echo off
echo ============================================================================
echo   AURA - Multilingual Android AI Voice & Automation Assistant
echo ============================================================================
cd /d "%~dp0"

echo [1/3] Checking Python dependencies...
python -m pip install -r requirements.txt --quiet

echo [2/3] Starting Aura AI Brain Server (FastAPI + WebSocket Live Bridge)...
start "" http://localhost:8000

echo.
echo ============================================================================
echo   AURA IS RUNNING!
echo   - Web UI on PC:         http://localhost:8000
echo   - Phone Wi-Fi Access:   Open Chrome on phone, navigate to your PC's IP:8000
echo   - Download Android APK: http://localhost:8000/download/AuraAssistant.apk
echo ============================================================================
echo.
python -m uvicorn app:app --host 0.0.0.0 --port 8000
pause
