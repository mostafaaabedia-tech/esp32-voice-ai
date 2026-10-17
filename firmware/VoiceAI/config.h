// ============================================================================
//  VoiceAI - user configuration
//  Edit these values, then upload. Most of them can also be changed later
//  from the phone app (Settings -> Device) and are saved in the ESP32 flash.
// ============================================================================
#pragma once

// ---- WiFi -------------------------------------------------------------------
#define WIFI_SSID        "1"
#define WIFI_PASSWORD    "123456789"
#define DEVICE_HOSTNAME  "voiceai"          // -> http://voiceai.local (mDNS)

// ---- PC running Ollama --------------------------------------------------------
// The PC must run Ollama with OLLAMA_HOST=0.0.0.0 so the ESP32 can reach it.
#define DEFAULT_OLLAMA_HOST  "192.168.1.189" // your PC's LAN IP
#define DEFAULT_OLLAMA_PORT  11434
#define DEFAULT_MODEL        "qwen2.5:1.5b"

// ---- AI behaviour -------------------------------------------------------------
#define DEFAULT_SYSTEM_PROMPT \
  "You are a helpful voice assistant running on a small ESP32 speaker. " \
  "Answer clearly and briefly in plain sentences. No markdown, no emojis, no lists."
#define DEFAULT_MAX_WORDS    40    // soft limit put into the system prompt
#define DEFAULT_NUM_PREDICT  160   // hard limit on generated tokens
#define DEFAULT_NUM_CTX      2048  // context window sent to Ollama
#define OLLAMA_TIMEOUT_MS    90000 // first answer can be slow while the model loads

// ---- Voice --------------------------------------------------------------------
// 0 = SAM  : robot voice generated ON the ESP32, works fully offline
// 1 = PC   : nicer voice from pc/tts_server.py running on your PC
#define DEFAULT_VOICE_MODE   0
#define DEFAULT_TTS_PORT     5050
#define DEFAULT_VOLUME       2.0f  // 0.0 - 4.0 (gain into the PAM8406)

// ---- Hardware -----------------------------------------------------------------
// Audio leaves the ESP32 on GPIO25 (internal DAC channel 1) -> PAM8406 L-IN.
#define STATUS_LED_PIN       2     // on-board LED on most ESP32 DevKit boards
