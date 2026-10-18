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
#define DEFAULT_OLLAMA_HOST  "192.168.80.189" // your PC's LAN IP
#define DEFAULT_OLLAMA_PORT  11434
#define DEFAULT_MODEL        "qwen2.5:1.5b"

// ---- AI behaviour -------------------------------------------------------------
#define DEFAULT_SYSTEM_PROMPT \
  "You are a helpful voice assistant running on a small ESP32 speaker. " \
  "Answer clearly and briefly in plain sentences. No markdown, no emojis, no lists."
#define DEFAULT_MAX_WORDS    40    // soft limit put into the system prompt
#define DEFAULT_NUM_PREDICT  160   // hard limit on generated tokens
#define DEFAULT_NUM_CTX      0     // 0 = use the model's default (fastest, no engine restart)
#define OLLAMA_TIMEOUT_MS    65000 // first answer can be slow while the model loads (max 65535)

// ---- Voice --------------------------------------------------------------------
// 0 = SAM  : robot voice generated ON the ESP32, works fully offline
// 1 = PC   : nicer voice from pc/tts_server.py running on your PC
#define DEFAULT_VOICE_MODE   0
#define DEFAULT_TTS_PORT     5050
#define DEFAULT_VOLUME       2.0f  // 0.0 - 4.0 (gain into the PAM8406)

// ---- OLED display (1.5 inch, 128x128) -----------------------------------------
// Look at the back of your display or its shop page and pick the chip:
//   1 = SSD1327  grayscale, I2C  (4 pins: VCC GND SCL SDA)  <- most common 1.5"
//   2 = SSD1351  full colour, SPI (7 pins: VCC GND DIN CLK CS DC RST)
//   3 = SH1107   monochrome, I2C (4 pins)
//   0 = no display
#ifndef OLED_TYPE
#define OLED_TYPE        1
#endif
#define OLED_ROTATION    0      // 0-3, turn the picture if the screen is mounted sideways
// I2C screens (type 1 and 3)
#define OLED_SDA         21
#define OLED_SCL         22
#define OLED_I2C_ADDR    0x3D   // SSD1327 is usually 0x3D, SH1107 0x3C (the other one is tried too)
// SPI screen (type 2)
#define OLED_SCK         18     // CLK / SCL on the board
#define OLED_MOSI        23     // DIN / SDA on the board
#define OLED_CS          5
#define OLED_DC          16
#define OLED_RST         17     // set -1 if your I2C board has no RST pin (type 1 and 3 ignore it if -1)

// ---- Hardware -----------------------------------------------------------------
// Audio leaves the ESP32 on GPIO25 (internal DAC channel 1) -> PAM8406 L-IN.
#define STATUS_LED_PIN       2     // on-board LED on most ESP32 DevKit boards
