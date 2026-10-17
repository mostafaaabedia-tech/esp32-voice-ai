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
