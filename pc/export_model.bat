@echo off
REM Copies the qwen2.5:1.5b model into the "ollama-export" folder next to this file.
REM Optional: export_model.bat other-model:tag
set MODEL=%1
if "%MODEL%"=="" set MODEL=qwen2.5:1.5b
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0export_model.ps1" -Model %MODEL%
pause
