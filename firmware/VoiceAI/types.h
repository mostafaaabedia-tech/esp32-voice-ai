// Types shared by the sketch. They live in a header because the Arduino IDE
// adds function prototypes at the top of the .ino, before any struct in it.
#pragma once
#include <Arduino.h>

struct Usage {
  uint32_t promptTokens = 0, completionTokens = 0;
  uint32_t totalMs = 0, loadMs = 0, promptMs = 0, genMs = 0;
  float tokensPerSec = 0;
};

// ST_SPEAKING = the answer is being shown on the OLED (and read out by the phone)
enum JobState { ST_IDLE, ST_THINKING, ST_SPEAKING, ST_ERROR };

// What the OLED face should show (filled by getFaceInfo() in VoiceAI.ino)
enum FaceMode { FACE_BOOT, FACE_WIFI, FACE_IDLE, FACE_LISTENING, FACE_THINKING, FACE_SPEAKING, FACE_ERROR };

// What the ultrasonic + IR sensors see (sensors.h)
struct SenseInfo {
  bool ultraOk = false;         // the ultrasonic sensor answered at least once
  bool hasLR = false;           // two ultrasonic sensors -> we know left / right
  int distCm = -1;              // nearest thing in front, -1 = nothing in range
  int dist1Cm = -1, dist2Cm = -1;
  float lookX = 0;              // -1 = person on the left .. +1 = on the right
  bool present = false;         // a person is standing in front
  uint32_t presentSinceMs = 0;  // when they arrived
  uint32_t absentSinceMs = 0;   // when they left
  uint32_t arrivedMs = 0;       // last arrival after being away for a while (greeting)
  uint32_t awayBeforeMs = 0;    // how long nobody was there before that arrival
  bool irOk = false;            // IR sensor configured
  bool petting = false;         // a hand is on the IR sensor right now
  uint32_t petSinceMs = 0;      // when this pet started
  uint32_t lastPetMs = 0;       // when the last pet ended
  uint16_t pets = 0;            // pets since boot
  uint8_t love = 0;             // 0..100, goes up while petted, slowly goes down
};

struct FaceInfo {
  FaceMode mode = FACE_BOOT;
  bool wifiOk = false;
  int rssi = 0;
  bool aiOnline = false;
  uint32_t modeSinceMs = 0;     // millis() when this mode started
  char ip[16] = "";
  char text[160] = "";          // reply / error text for the scrolling line
  char sub[720] = "";           // the answer, shown word by word like subtitles
  int subPos = -1;              // index in sub[] being read right now (-1 = none)
  char heard[200] = "";         // what you are saying (live from the phone mic)
  char note[32] = "";           // e.g. "restarted: power dip" for the first minute
  SenseInfo s;                  // sensors
};
