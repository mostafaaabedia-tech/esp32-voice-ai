// ============================================================================
//  1.5" OLED (128x128) animated face for VoiceAI
//
//  Pick your display in config.h  ->  OLED_TYPE
//    1 = SSD1327  grayscale, I2C   (most common 1.5" white OLED)
//    2 = SSD1351  full colour, SPI
//    3 = SH1107   monochrome, I2C
//    0 = no display
//
//  Everything is drawn into a 128x128 colour canvas in RAM, then copied to the
//  screen, so the same animation works on all three displays.
//  It runs in its own task on core 0, so the face keeps moving while the ESP32
//  waits for Ollama or speaks.
//
//  Moods:  boot logo -> WiFi spinner -> idle (blinks, looks around, falls asleep)
//          listening (wide eyes + sound rings)  thinking (eyes up + orbit dots)
//          speaking (happy eyes + mouth that follows the real audio)
//          error (sad eyes + message)
// ============================================================================
#pragma once
#include "config.h"
#include "types.h"

FaceInfo getFaceInfo();   // defined in VoiceAI.ino

#if OLED_TYPE == 0
inline void oledBegin() {}
#else

#include <Adafruit_GFX.h>
#if OLED_TYPE == 1
  #include <Wire.h>
  #include <Adafruit_SSD1327.h>
#elif OLED_TYPE == 2
  #include <SPI.h>
  #include <Adafruit_SSD1351.h>
#elif OLED_TYPE == 3
  #include <Wire.h>
  #include <Adafruit_SH110X.h>
#else
  #error "OLED_TYPE must be 0, 1, 2 or 3"
#endif

namespace oled {

const int W = 128, H = 128;
GFXcanvas16 *cv = nullptr;
bool ok = false;

#if OLED_TYPE == 1
Adafruit_SSD1327 *scr = nullptr;
#elif OLED_TYPE == 2
Adafruit_SSD1351 *scr = nullptr;
#else
Adafruit_SH1107 *scr = nullptr;
#endif

// ------------------------------------------------------------- colours -----
inline uint16_t rgb(uint8_t r, uint8_t g, uint8_t b) {
  return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
}
const uint16_t BLACK = 0;
const uint16_t WHITE = 0xFFFF;
uint16_t TEAL, BLUE, DIM, RED, GREEN, AMBER, NAVY;

inline uint16_t mix(uint16_t a, uint16_t b, float t) {   // blend two colours
  t = constrain(t, 0.f, 1.f);
  int ar = (a >> 11) & 31, ag = (a >> 5) & 63, ab = a & 31;
  int br = (b >> 11) & 31, bg = (b >> 5) & 63, bb = b & 31;
  return ((int)(ar + (br - ar) * t) << 11) | ((int)(ag + (bg - ag) * t) << 5) | (int)(ab + (bb - ab) * t);
}

// --------------------------------------------------------- push to screen --
void flush() {
#if OLED_TYPE == 2
  scr->startWrite();
  scr->setAddrWindow(0, 0, W, H);
  scr->writePixels(cv->getBuffer(), W * H, true, false);
  scr->endWrite();
#else
  uint16_t *p = cv->getBuffer();
  for (int y = 0; y < H; y++) {
    for (int x = 0; x < W; x++) {
      uint16_t c = *p++;
      uint8_t r = (c >> 11) << 3, g = ((c >> 5) & 63) << 2, b = (c & 31) << 3;
      uint8_t m = max(r, max(g, b));                  // brightness
  #if OLED_TYPE == 1
      scr->drawPixel(x, y, m >> 4);                   // 16 gray levels
  #else
      scr->drawPixel(x, y, m >= 90 ? SH110X_WHITE : SH110X_BLACK);
  #endif
    }
  }
  scr->display();
#endif
}

// ---------------------------------------------------------------- text -----
void centerText(const char *s, int y, uint16_t color, uint8_t size = 1) {
  int16_t x1, y1;
  uint16_t w, h;
  cv->setTextSize(size);
  cv->setTextWrap(false);
  cv->getTextBounds(s, 0, 0, &x1, &y1, &w, &h);
  cv->setTextColor(color);
  cv->setCursor((W - (int)w) / 2, y);
  cv->print(s);
}

/** One line of text that scrolls from right to left. */
void marquee(const char *s, int y, uint16_t color, uint32_t t) {
  int len = strlen(s);
  if (!len) return;
  int px = len * 6;
  cv->setTextSize(1);
  cv->setTextWrap(false);
  cv->setTextColor(color);
  if (px <= W - 8) {
    centerText(s, y, color);
    return;
  }
  int span = px + 40;
  int off = (t / 28) % span;   // ~36 px per second
  cv->setCursor(W - off, y);
  cv->print(s);
  cv->setCursor(W - off + span, y);
  cv->print(s);
}

// ------------------------------------------------------- status icons ------
void wifiIcon(int x, int y, int rssi, bool on) {
  int bars = !on ? 0 : rssi > -55 ? 4 : rssi > -65 ? 3 : rssi > -75 ? 2 : 1;
  for (int i = 0; i < 4; i++) {
    int h = 2 + i * 2;
    uint16_t c = i < bars ? WHITE : DIM;
    cv->fillRect(x + i * 3, y + 8 - h, 2, h, c);
  }
}

void statusBar(const FaceInfo &f) {
  wifiIcon(4, 2, f.rssi, f.wifiOk);
  cv->setTextSize(1);
  cv->setTextColor(f.aiOnline ? GREEN : RED);
  cv->setCursor(W - 22, 3);
  cv->print("AI");
  cv->fillCircle(W - 6, 6, 2, f.aiOnline ? GREEN : RED);
}

// ---------------------------------------------------------------- eyes -----
struct Eye {
  float open = 1, tOpen = 1;      // 0 closed .. 1 open (>1 = wide)
  float lx = 0, ly = 0, tlx = 0, tly = 0;
};
Eye eyeL, eyeR;
uint32_t nextBlink = 0, blinkStart = 0, nextLook = 0;
bool doubleBlink = false;

inline float approach(float v, float t, float k) { return v + (t - v) * k; }

/** Teal-to-blue gradient eye (on mono/gray screens it becomes a soft glow). */
void drawEye(int cx, int cy, float w, float h, uint16_t top, uint16_t bottom, bool shine = true) {
  int iw = max(4, (int)w), ih = max(3, (int)h);
  int x = cx - iw / 2, y = cy - ih / 2;
  int r = min(10, min(iw, ih) / 2);
  cv->fillRoundRect(x, y, iw, ih, r, top);
  // recolour the eye from top colour to bottom colour, row by row
  for (int row = 0; row < ih; row++) {
    uint16_t c = mix(top, bottom, (float)row / ih);
    for (int col = 0; col < iw; col++) {
      if (cv->getPixel(x + col, y + row) == top) cv->drawPixel(x + col, y + row, c);
    }
  }
  // small shine dot
  if (shine && ih > 14) cv->fillCircle(x + iw - 8, y + 7, 3, WHITE);
}

enum Shape { NORMAL, HAPPY, SAD };

float eyeScale = 1, eyeCy = 54;   // animated: eyes shrink and move up for subtitles

void drawEyes(Shape shape, uint32_t now, float scale = 1, float centerY = 54) {
  eyeScale = approach(eyeScale, scale, 0.2f);
  eyeCy = approach(eyeCy, centerY, 0.2f);
  const float baseW = 30 * eyeScale, baseH = 38 * eyeScale;
  const int cy = (int)eyeCy;
  Eye *eyes[2] = {&eyeL, &eyeR};
  const int gapX = (int)(26 * eyeScale);
  const int cxs[2] = {64 - gapX, 64 + gapX};

  // blinking
  float blink = 1;
  if (now >= nextBlink) {
    blinkStart = now;
    doubleBlink = random(100) < 20;
    nextBlink = now + random(2200, 5500);
  }
  uint32_t bt = now - blinkStart;
  if (bt < 140) blink = 1 - sinf(PI * bt / 140.f) * 0.92f;
  else if (doubleBlink && bt > 220 && bt < 360) blink = 1 - sinf(PI * (bt - 220) / 140.f) * 0.92f;

  for (int i = 0; i < 2; i++) {
    Eye &e = *eyes[i];
    e.open = approach(e.open, e.tOpen, 0.22f);
    e.lx = approach(e.lx, e.tlx, 0.18f);
    e.ly = approach(e.ly, e.tly, 0.18f);
    float h = baseH * e.open * blink;
    float w = baseW * (1 + (1 - min(1.f, e.open)) * 0.15f);   // squint = a little wider
    int cx = cxs[i] + (int)e.lx, ey = cy + (int)e.ly;
    drawEye(cx, ey, w, h, TEAL, BLUE, shape == NORMAL);

    if (shape == HAPPY && h > 10) {
      // a big black circle eats the bottom -> smiling arch eyes, like  ^  ^
      int R = (int)(w * 0.52f);
      int top = ey - (int)h / 2;
      cv->fillCircle(cx, top + (int)(h * 0.34f) + R, R, BLACK);
      cv->fillRect(cx - (int)w / 2 - 2, top + (int)(h * 0.34f) + R, (int)w + 4, (int)h, BLACK);
    } else if (shape == SAD && h > 8) {
      // eyelid sloping down to the outside
      int x0 = cx - (int)w / 2 - 2, x1 = cx + (int)w / 2 + 2, top = ey - (int)h / 2 - 1;
      if (i == 0) cv->fillTriangle(x0, top, x1, top, x0, top + (int)(h * 0.55f), BLACK);
      else cv->fillTriangle(x0, top, x1, top, x1, top + (int)(h * 0.55f), BLACK);
    }
  }
}

void lookAround(uint32_t now, float range = 1) {
  if (now < nextLook) return;
  nextLook = now + random(1300, 4200);
  float x = random(-11, 12) * range, y = random(-6, 7) * range;
  if (random(100) < 35) { x = 0; y = 0; }            // often look straight at you
  eyeL.tlx = eyeR.tlx = x;
  eyeL.tly = eyeR.tly = y;
}

void setEyes(float open, float lx, float ly) {
  eyeL.tOpen = eyeR.tOpen = open;
  eyeL.tlx = eyeR.tlx = lx;
  eyeL.tly = eyeR.tly = ly;
}

// ------------------------------------------------------------ screens ------
void bootScreen(uint32_t t) {
  // ring that draws itself, then the name fades in
  float p = min(1.f, t / 1200.f);
  int cx = 64, cy = 50, r = 30;
  for (int a = 0; a < (int)(360 * p); a += 2) {
    float rad = (a - 90) * DEG_TO_RAD;
    uint16_t c = mix(TEAL, BLUE, a / 360.f);
    cv->fillCircle(cx + cosf(rad) * r, cy + sinf(rad) * r, 2, c);
  }
  if (t > 500) {
    float s = min(1.f, (t - 500) / 600.f);
    cv->fillCircle(cx, cy, (int)(12 * s), TEAL);
    cv->fillCircle(cx, cy, (int)(5 * s), BLACK);
  }
  if (t > 900) {
    float f = min(1.f, (t - 900) / 600.f);
    centerText("VoiceAI", 90, mix(BLACK, WHITE, f), 2);
  }
  if (t > 1400) {
    float f = min(1.f, (t - 1400) / 600.f);
    centerText("Mostafa M. Aabedi &", 110, mix(BLACK, DIM, f));
    centerText("Sebghatullah Haidari", 119, mix(BLACK, DIM, f));
  }
}

void wifiScreen(uint32_t now) {
  // spinner made of 8 dots with a fading tail
  int cx = 64, cy = 54;
  int head = (now / 90) % 8;
  for (int i = 0; i < 8; i++) {
    float a = i * PI / 4 - PI / 2;
    int age = (head - i + 8) % 8;
    uint16_t c = mix(TEAL, BLACK, age / 8.f);
    cv->fillCircle(cx + cosf(a) * 20, cy + sinf(a) * 20, 4 - age / 3, c);
  }
  centerText("Connecting", 92, WHITE);
  centerText(WIFI_SSID, 106, DIM);
}

void listeningExtras(uint32_t t) {
  // sound rings growing out from the bottom centre
  for (int k = 0; k < 3; k++) {
    float p = fmodf(t / 900.f + k / 3.f, 1.f);
    uint16_t c = mix(TEAL, BLACK, p);
    cv->drawCircle(64, 100, 4 + (int)(p * 13), c);
  }
  cv->fillCircle(64, 100, 4, TEAL);
  centerText("listening...", 119, DIM);
}

void thinkingExtras(uint32_t t) {
  // three dots orbiting
  for (int k = 0; k < 3; k++) {
    float a = t / 260.f + k * 2.094f;
    int x = 64 + cosf(a) * 12, y = 102 + sinf(a) * 6;
    cv->fillCircle(x, y, 3 - k / 2, mix(TEAL, BLUE, k / 2.f));
  }
  char buf[24];
  snprintf(buf, sizeof buf, "thinking %lus", (unsigned long)(t / 1000));
  centerText(buf, 119, DIM);
}

float mouthSmooth = 0;
void mouth(const FaceInfo &f, uint32_t now, int cy, float maxH) {
  mouthSmooth = approach(mouthSmooth, f.audioLevel / 255.f, 0.45f);
  float lvl = max(mouthSmooth, 0.12f);
  const int bars = 9, bw = 4, gap = 3;
  int x0 = 64 - (bars * (bw + gap) - gap) / 2;
  for (int i = 0; i < bars; i++) {
    float env = 1 - fabsf(i - (bars - 1) / 2.f) / ((bars + 1) / 2.f);   // taller in the middle
    float wob = 0.55f + 0.45f * sinf(now / 70.f + i * 1.3f);
    int h = 2 + (int)(maxH * lvl * env * wob);
    cv->fillRoundRect(x0 + i * (bw + gap), cy - h / 2, bw, h, 2, mix(TEAL, BLUE, (float)i / bars));
  }
}

// ------------------------------------------------------------ subtitles ----
// Karaoke style: words already spoken are white, the word being spoken now is
// highlighted, the next words are dim. Lines scroll up as the voice goes on.
const int SUB_TOP = 74, SUB_LINE = 13, SUB_LINES = 4, SUB_CHARS = 20;
float subScroll = 0;
int lastLine = 0;

struct Word { int16_t start, len, line, col; };

void subtitles(const FaceInfo &f) {
  static Word words[160];
  int n = 0, line = 0, col = 0;
  const char *s = f.sub;
  int len = strlen(s);
  // word wrap into lines of SUB_CHARS characters
  for (int i = 0; i < len && n < 160;) {
    while (i < len && s[i] == ' ') i++;
    if (i >= len) break;
    int st = i;
    while (i < len && s[i] != ' ') i++;
    int wl = min(i - st, SUB_CHARS);
    if (col > 0 && col + 1 + wl > SUB_CHARS) { line++; col = 0; }
    if (col > 0) col++;
    words[n++] = {(int16_t)st, (int16_t)wl, (int16_t)line, (int16_t)col};
    col += wl;
  }
  if (!n) return;

  // which word is being spoken?
  int cur = 0;
  for (int k = 0; k < n; k++) if (words[k].start <= f.subPos) cur = k;
  int target = max(0, words[cur].line - 1);           // keep the current line second from top
  if (target + SUB_LINES > line + 1) target = max(0, line + 1 - SUB_LINES);
  subScroll = approach(subScroll, target * SUB_LINE, 0.25f);

  cv->setTextSize(1);
  cv->setTextWrap(false);
  for (int k = 0; k < n; k++) {
    const Word &w = words[k];
    int y = SUB_TOP + w.line * SUB_LINE - (int)subScroll;
    if (y < SUB_TOP - 2 || y > SUB_TOP + (SUB_LINES - 1) * SUB_LINE + 2) continue;
    int lineLen = 0;                                   // centre each line
    for (int j = 0; j < n; j++) if (words[j].line == w.line) lineLen = words[j].col + words[j].len;
    int x = (W - lineLen * 6) / 2 + w.col * 6;
    uint16_t color;
    if (k == cur) {
      cv->fillRoundRect(x - 2, y - 2, w.len * 6 + 3, 11, 2, TEAL);
      color = BLACK;
    } else {
      color = k < cur ? WHITE : DIM;
    }
    cv->setTextColor(color);
    for (int c = 0; c < w.len; c++) {
      cv->setCursor(x + c * 6, y);
      cv->print(s[w.start + c]);
    }
  }
}

void speakingExtras(const FaceInfo &f, uint32_t now, uint32_t t) {
  if (f.subPos >= 0 && f.sub[0]) {
    mouth(f, now, 62, 9);
    subtitles(f);
  } else {
    mouth(f, now, 100, 18);
    marquee(f.text, 119, WHITE, t);
  }
}

void sleepExtras(uint32_t now) {
  for (int k = 0; k < 3; k++) {
    float p = fmodf(now / 2600.f + k / 3.f, 1.f);
    int x = 96 + (int)(p * 18), y = 40 - (int)(p * 30);
    cv->setTextSize(1 + (k == 2));
    cv->setTextColor(mix(WHITE, BLACK, p));
    cv->setCursor(x, y);
    cv->print("z");
  }
}

// --------------------------------------------------------------- frame -----
FaceMode lastMode = FACE_BOOT;

void frame(uint32_t now) {
  FaceInfo f = getFaceInfo();
  uint32_t t = now - f.modeSinceMs;
  cv->fillScreen(BLACK);

  if (f.mode != lastMode) {
    lastMode = f.mode;
    nextLook = now + 400;
  }

  switch (f.mode) {
    case FACE_BOOT:
      bootScreen(t);
      break;

    case FACE_WIFI:
      wifiScreen(now);
      break;

    case FACE_IDLE: {
      bool sleepy = t > 120000;                       // 2 min without activity
      statusBar(f);
      if (sleepy) {
        setEyes(0.18f, 0, 6);
        drawEyes(NORMAL, now);
        sleepExtras(now);
      } else {
        eyeL.tOpen = eyeR.tOpen = 1;
        lookAround(now);
        drawEyes(NORMAL, now);
      }
      if (!f.aiOnline) centerText("PC / Ollama offline", 104, AMBER);
      centerText(f.ip, 119, DIM);
      break;
    }

    case FACE_LISTENING:
      statusBar(f);
      setEyes(1.15f, 0, -2);
      drawEyes(NORMAL, now);
      listeningExtras(t);
      break;

    case FACE_THINKING:
      statusBar(f);
      setEyes(0.72f, 9, -8);                          // looking up and to the side
      drawEyes(NORMAL, now);
      thinkingExtras(t);
      break;

    case FACE_SPEAKING:
      statusBar(f);
      setEyes(1, 0, -2);
      if (f.subPos >= 0 && f.sub[0]) drawEyes(HAPPY, now, 0.62f, 34);   // small eyes, room for subtitles
      else drawEyes(HAPPY, now);
      speakingExtras(f, now, t);
      break;

    case FACE_ERROR:
      statusBar(f);
      setEyes(0.75f, 0, 4);
      drawEyes(SAD, now);
      marquee(f.text[0] ? f.text : "Something went wrong", 112, RED, t);
      break;
  }
  flush();
}

void task(void *) {
  const TickType_t period = pdMS_TO_TICKS(OLED_TYPE == 2 ? 33 : 50);   // ~30 / 20 fps
  TickType_t last = xTaskGetTickCount();
  for (;;) {
    frame(millis());
    vTaskDelayUntil(&last, period);
  }
}

}  // namespace oled

/** Start the display and its animation task. Safe to call if no screen is connected. */
inline void oledBegin() {
  using namespace oled;
  TEAL = rgb(46, 196, 201);
  BLUE = rgb(59, 130, 246);
  DIM = rgb(110, 130, 150);
  RED = rgb(255, 100, 90);
  GREEN = rgb(110, 210, 140);
  AMBER = rgb(240, 180, 90);
  NAVY = rgb(21, 64, 106);

#if OLED_TYPE == 1
  Wire.begin(OLED_SDA, OLED_SCL);
  scr = new Adafruit_SSD1327(128, 128, &Wire, OLED_RST, 1000000, 400000);
  ok = scr->begin(OLED_I2C_ADDR) || scr->begin(OLED_I2C_ADDR == 0x3D ? 0x3C : 0x3D);
#elif OLED_TYPE == 2
  SPI.begin(OLED_SCK, -1, OLED_MOSI, OLED_CS);
  scr = new Adafruit_SSD1351(128, 128, &SPI, OLED_CS, OLED_DC, OLED_RST);
  scr->begin(18000000);
  ok = true;   // SPI screens can't report if they are connected
#elif OLED_TYPE == 3
  Wire.begin(OLED_SDA, OLED_SCL);
  Wire.setClock(400000);
  scr = new Adafruit_SH1107(128, 128, &Wire, OLED_RST, 1000000, 400000);
  ok = scr->begin(OLED_I2C_ADDR) || scr->begin(OLED_I2C_ADDR == 0x3C ? 0x3D : 0x3C);
#endif
  if (!ok) {
    Serial.println("[oled] display not found - check wiring and OLED_TYPE in config.h");
    return;
  }
  scr->setRotation(OLED_ROTATION);
  cv = new GFXcanvas16(W, H);
  if (!cv || !cv->getBuffer()) {
    Serial.println("[oled] not enough memory for the frame buffer");
    ok = false;
    return;
  }
  randomSeed(esp_random());
  xTaskCreatePinnedToCore(task, "oled", 8192, nullptr, 1, nullptr, 0);
  Serial.println("[oled] animated face running");
}
#endif
