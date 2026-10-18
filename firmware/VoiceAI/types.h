// Types shared by the sketch. They live in a header because the Arduino IDE
// adds function prototypes at the top of the .ino, before any struct in it.
#pragma once
#include <Arduino.h>

struct Usage {
  uint32_t promptTokens = 0, completionTokens = 0;
  uint32_t totalMs = 0, loadMs = 0, promptMs = 0, genMs = 0;
  float tokensPerSec = 0;
};

enum JobState { ST_IDLE, ST_THINKING, ST_SPEAKING, ST_ERROR };

// What the OLED face should show (filled by getFaceInfo() in VoiceAI.ino)
enum FaceMode { FACE_BOOT, FACE_WIFI, FACE_IDLE, FACE_LISTENING, FACE_THINKING, FACE_SPEAKING, FACE_ERROR };

struct FaceInfo {
  FaceMode mode = FACE_BOOT;
  bool wifiOk = false;
  int rssi = 0;
  bool aiOnline = false;
  uint32_t modeSinceMs = 0;     // millis() when this mode started
  uint8_t audioLevel = 0;       // 0..255 speaker loudness (for the mouth)
  char ip[16] = "";
  char text[160] = "";          // reply / error text for the scrolling line
  char sub[720] = "";           // subtitle: exactly what the speaker is saying
  int subPos = -1;              // index in sub[] being spoken right now (-1 = none)
  char heard[200] = "";         // what you are saying (live from the phone mic)
};
