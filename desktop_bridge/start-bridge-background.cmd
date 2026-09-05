@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-bridge-background.ps1"
if errorlevel 1 (
    echo.
    set /p "_=Startup failed. Press Enter to close..."
    exit /b 1
)
echo.
set /p "_=Bridge is running in the background. Press Enter to close..."
