@echo off
echo Starting DS2 Step Tracker PC Server...
cd /d "%~dp0\pc_server"

if exist "..\.venv\Scripts\activate.bat" (
    call "..\.venv\Scripts\activate.bat"
) else if exist "..\venv\Scripts\activate.bat" (
    call "..\venv\Scripts\activate.bat"
) else (
    echo [Warning] No virtual environment found. Using global python...
)

python server.py
pause
