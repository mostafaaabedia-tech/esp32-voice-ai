@echo off
REM Run on the NEW laptop (right-click > Run as administrator), after installing Ollama.
set MODEL=%1
if "%MODEL%"=="" set MODEL=qwen2.5:1.5b
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0import_model.ps1" -Model %MODEL%
pause
