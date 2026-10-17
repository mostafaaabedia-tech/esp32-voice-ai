@echo off
REM Lets the ESP32 reach Ollama on this PC over WiFi.
REM Close Ollama from the tray icon first, then double-click this file.
set OLLAMA_HOST=0.0.0.0:11434
set OLLAMA_KEEP_ALIVE=30m
echo Pulling qwen2.5:1.5b (only the first time)...
ollama pull qwen2.5:1.5b
echo.
echo Your PC IP addresses (put the WiFi one in config.h / the app):
ipconfig | findstr /i "IPv4"
echo.
echo Starting Ollama for the whole network... keep this window open.
ollama serve
