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
  "You are a cute, helpful AI assistant living in a small ESP32 robot with an OLED face. " \
  "Answer clearly and briefly in plain sentences. No markdown, no emojis, no lists."
#define DEFAULT_MAX_WORDS    40    // soft limit put into the system prompt
#define DEFAULT_NUM_PREDICT  160   // hard limit on generated tokens
#define DEFAULT_NUM_CTX      0     // 0 = use the model's default (fastest, no engine restart)
#define OLLAMA_TIMEOUT_MS    65000 // first answer can be slow while the model loads (max 65535)
#define DEFAULT_READ_MS_PER_CHAR 60 // answer subtitles on the OLED: ms per letter (lower = faster)

// ---- OLED display (1.5 inch, 128x128) -----------------------------------------
// Look at the back of your display or its shop page and pick the chip:
//   1 = SSD1327  grayscale, I2C  (4 pins, address 0x3D)
//   2 = SSD1351  full colour, SPI (7 pins: VCC GND DIN CLK CS DC RST)
//   3 = SH1107   white, I2C (4 pins SDA SCL GND VCC, address 0x3C)  <- your screen
//   Tip: if the screen shows random "noise" dots, you picked the wrong chip.
//   0 = no display
#ifndef OLED_TYPE
#define OLED_TYPE        3
#endif
#define OLED_ROTATION    0      // 0-3, turn the picture if the screen is mounted sideways
// I2C screens (type 1 and 3)
#define OLED_SDA         21
#define OLED_SCL         22
#define OLED_I2C_ADDR    0x3C   // SH1107 is usually 0x3C, SSD1327 0x3D (the other one is tried too)
// SPI screen (type 2)
#define OLED_SCK         18     // CLK / SCL on the board
#define OLED_MOSI        23     // DIN / SDA on the board
#define OLED_CS          5
#define OLED_DC          16
#define OLED_RST         17     // set -1 if your I2C board has no RST pin (type 1 and 3 ignore it if -1)

// ---- Sensors ------------------------------------------------------------------
// Ultrasonic HC-SR04: the eyes follow how near / far you are.
//   HC-SR04 VCC -> 5V (VIN), GND -> GND, TRIG -> ULTRA_TRIG,
//   ECHO -> 1k resistor -> ULTRA_ECHO, and 2k resistor from ULTRA_ECHO to GND
//   (ECHO gives 5 V, the ESP32 pin only takes 3.3 V). The HC-SR04P / RCWL-1601
//   3.3 V versions can be connected directly.
#define ULTRA_TRIG       26
#define ULTRA_ECHO       27
// Optional SECOND ultrasonic for left / right tracking. Mount sensor 1 on the
// LEFT and sensor 2 on the RIGHT (as you look at the screen), ~10 cm apart.
// Leave -1 if you only have one.
#ifndef ULTRA2_TRIG
#define ULTRA2_TRIG      -1     // e.g. 32
#define ULTRA2_ECHO      -1     // e.g. 33
#endif
#define EYES_FLIP_X      0      // 1 if the eyes look the wrong way left/right
// IR obstacle sensor (FC-51 / TCRT5000): wave or hold your hand over it to pet the AI.
//   IR VCC -> 3V3, GND -> GND, OUT -> IR_PIN
#define IR_PIN           34     // -1 if you have no IR sensor
#define IR_ACTIVE_LOW    1      // most IR modules give LOW when they see your hand
#define PRESENCE_CM      120    // someone closer than this = "a person is here"
#define TOO_CLOSE_CM     8      // closer than this = "too close!" squint

// ---- Hardware -----------------------------------------------------------------
#define STATUS_LED_PIN       2     // on-board LED on most ESP32 DevKit boards
