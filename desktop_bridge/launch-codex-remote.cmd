@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch-codex-remote.ps1"
if errorlevel 1 pause
