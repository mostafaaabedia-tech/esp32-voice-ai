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
//  waits for Ollama.
//
//  Moods:  boot logo -> WiFi spinner -> idle: follows you with its eyes
//          (ultrasonic), says hi when you arrive, squints if you get too close,
//          gets bored and falls asleep when you leave, yawns when you come back,
//          winks / rolls its eyes / wiggles / heart eyes now and then
//          petting (IR sensor): happy eyes, blush, floating hearts, love meter
//          listening (wide eyes + your words live)  thinking (eyes up + orbit dots)
//          answer (happy eyes + talking mouth + karaoke subtitles)
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
uint16_t TEAL, BLUE, DIM, RED, GREEN, AMBER, NAVY, PINK;

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
  if (f.s.ultraOk && f.s.present && f.s.distCm > 0) {   // how far you are
    char b[12];
    snprintf(b, sizeof b, "%dcm", f.s.distCm);
    centerText(b, 3, DIM);
  }
  if (f.s.love >= 10) {                                   // small heart = it likes you
    float r = 2.5f + f.s.love / 40.f;
    int cx = 25, cy = 6;
    int rr = max(1, (int)(r * 0.55f));
    cv->fillCircle(cx - (int)(r * 0.48f), cy - (int)(r * 0.25f), rr, PINK);
    cv->fillCircle(cx + (int)(r * 0.48f), cy - (int)(r * 0.25f), rr, PINK);
    cv->fillTriangle(cx - (int)r, cy - (int)(r * 0.05f), cx + (int)r, cy - (int)(r * 0.05f), cx, cy + (int)(r * 0.95f), PINK);
  }
}

// ----------------------------------------------------------- particles -----
// Little things that float around the face: hearts when you pet it, sparkles
// when it says hi or winks.
enum PKind : uint8_t { P_HEART, P_SPARK };
struct Particle {
  float x0, y0, vx, vy, size;
  uint32_t born, life;
  PKind kind;
  bool on;
  float phase;
};
Particle parts[18];

void spawn(PKind k, float x, float y, float vx, float vy, uint32_t life, float size, uint32_t now) {
  for (auto &p : parts)
    if (!p.on) {
      p = {x, y, vx, vy, size, now, life, k, true, random(0, 628) / 100.f};
      return;
    }
}

void drawHeart(int cx, int cy, float r, uint16_t c) {
  if (r < 1.5f) { cv->fillCircle(cx, cy, 1, c); return; }
  int rr = max(1, (int)(r * 0.55f));
  cv->fillCircle(cx - (int)(r * 0.48f), cy - (int)(r * 0.25f), rr, c);
  cv->fillCircle(cx + (int)(r * 0.48f), cy - (int)(r * 0.25f), rr, c);
  cv->fillTriangle(cx - (int)r, cy - (int)(r * 0.05f), cx + (int)r, cy - (int)(r * 0.05f), cx, cy + (int)(r * 0.95f), c);
}

void drawSpark(int cx, int cy, int s, uint16_t c) {
  cv->drawFastHLine(cx - s, cy, 2 * s + 1, c);
  cv->drawFastVLine(cx, cy - s, 2 * s + 1, c);
  if (s >= 3) {
    int d = s / 2;
    cv->drawPixel(cx - d, cy - d, c); cv->drawPixel(cx + d, cy - d, c);
    cv->drawPixel(cx - d, cy + d, c); cv->drawPixel(cx + d, cy + d, c);
  }
}

void drawParts(uint32_t now) {
  for (auto &p : parts) {
    if (!p.on) continue;
    uint32_t age = now - p.born;
    if (age >= p.life) { p.on = false; continue; }
    float k = (float)age / p.life, sec = age / 1000.f;
    int x = (int)(p.x0 + p.vx * sec + (p.kind == P_HEART ? sinf(sec * 5 + p.phase) * 3 : 0));
    int y = (int)(p.y0 + p.vy * sec);
    float pop = k < 0.15f ? k / 0.15f : 1;                       // grow in quickly
    uint16_t c = mix(p.kind == P_HEART ? PINK : WHITE, BLACK, k * k);
    if (p.kind == P_HEART) drawHeart(x, y, p.size * pop, c);
    else drawSpark(x, y, max(1, (int)(p.size * (1 - k) + 1)), c);
  }
}

/** Thick line with round ends (for >< and ^^ eyes). */
void thickLine(float x0, float y0, float x1, float y1, float t, uint16_t c) {
  float dx = x1 - x0, dy = y1 - y0, L = sqrtf(dx * dx + dy * dy);
  int r = max(1, (int)(t / 2));
  if (L > 0.5f) {
    float nx = -dy / L * t / 2, ny = dx / L * t / 2;
    cv->fillTriangle(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, c);
    cv->fillTriangle(x0 + nx, y0 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, c);
  }
  cv->fillCircle(x0, y0, r, c);
  cv->fillCircle(x1, y1, r, c);
}

/** Speech bubble with a small tail, centred at cx. */
void bubble(const char *s, int cx, int y, uint8_t size, uint16_t c) {
  int w = strlen(s) * 6 * size + 12, h = 8 * size + 8;
  int x = cx - w / 2;
  cv->fillRoundRect(x, y, w, h, 6, BLACK);
  cv->drawRoundRect(x, y, w, h, 6, c);
  cv->fillTriangle(cx - 4, y + h - 1, cx + 4, y + h - 1, cx, y + h + 5, BLACK);
  cv->drawLine(cx - 4, y + h - 1, cx, y + h + 5, c);
  cv->drawLine(cx + 4, y + h - 1, cx, y + h + 5, c);
  centerText(s, y + 4, c, size);
}

/** "///" blush under the eyes (reads well even on a white-only screen). */
void blush(int cy, float sc) {
  for (int side = -1; side <= 1; side += 2) {
    int bx = 64 + side * (int)(30 * sc), by = cy + (int)(21 * sc);
    for (int k = 0; k < 3; k++) {
      int x = bx - 6 + k * 5;
      cv->drawLine(x, by + 3, x + 3, by - 2, PINK);
      cv->drawLine(x + 1, by + 3, x + 4, by - 2, PINK);
    }
  }
}

/** Love meter: heart + bar. */
void loveBar(uint8_t love, int y) {
  drawHeart(13, y + 3, 4.5f, PINK);
  cv->drawRoundRect(22, y, 94, 8, 4, DIM);
  int w = (int)(90 * love / 100.f);
  if (w > 2) cv->fillRoundRect(24, y + 2, w, 4, 2, PINK);
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
  // (on the white-only SH1107 a black dot reads like a pupil / reflection)
  if (shine && ih > 14) cv->fillCircle(x + iw - 8, y + 7, 3, OLED_TYPE == 3 ? BLACK : WHITE);
}

enum Shape { NORMAL, HAPPY, SAD, HEART, SQUEEZE, BLISS };

// animated values, smoothed every frame
float eyeScale = 1, eyeCy = 54;     // eyes shrink and move up for subtitles
float converge = 0, tConverge = 0;  // both eyes turn inward = focusing on something close
float lid = 0, tLid = 0;            // 0 .. 0.6 flat upper eyelid (bored / sleepy)
float lookK = 0.18f;                // how fast the eyes move to their target
int winkEye = -1;                   // 0 / 1 = this eye is winking
uint32_t winkStart = 0;

void drawEyes(Shape shape, uint32_t now, float scale = 1, float centerY = 54) {
  eyeScale = approach(eyeScale, scale, 0.2f);
  eyeCy = approach(eyeCy, centerY, 0.2f);
  converge = approach(converge, tConverge, 0.15f);
  lid = approach(lid, tLid, 0.15f);
  float sc = eyeScale * (1 + 0.022f * sinf(now / 850.f));   // gentle "breathing"
  const float baseW = 30 * sc, baseH = 38 * sc;
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
  if (shape == HEART || shape == SQUEEZE || shape == BLISS) blink = 1;

  for (int i = 0; i < 2; i++) {
    Eye &e = *eyes[i];
    e.open = approach(e.open, e.tOpen, 0.22f);
    e.lx = approach(e.lx, e.tlx, lookK);
    e.ly = approach(e.ly, e.tly, lookK);
    float wink = 1;
    if (i == winkEye) {
      uint32_t wt = now - winkStart;
      if (wt < 520) wink = 1 - sinf(PI * wt / 520.f) * 0.9f;
      else winkEye = -1;
    }
    float h = baseH * e.open * blink * wink;
    float w = baseW * (1 + (1 - min(1.f, e.open)) * 0.15f);   // squint = a little wider
    int cx = cxs[i] + (int)e.lx + (i == 0 ? (int)converge : -(int)converge);
    int ey = cy + (int)e.ly;

    switch (shape) {
      case HEART: {
        float pulse = 1 + 0.13f * sinf(now / 110.f);
        drawHeart(cx, ey, 15 * sc * pulse, PINK);
        break;
      }
      case SQUEEZE: {                               // >  <
        float hw = 10 * sc, hh = 11 * sc, d = i == 0 ? 1 : -1;
        thickLine(cx - d * hw, ey - hh, cx + d * hw, ey, 5 * sc, TEAL);
        thickLine(cx + d * hw, ey, cx - d * hw, ey + hh, 5 * sc, TEAL);
        break;
      }
      case BLISS: {                                 // ^  ^  (eyes closed, very happy)
        float hw = 12 * sc;
        thickLine(cx - hw, ey + 5 * sc, cx, ey - 6 * sc, 5 * sc, TEAL);
        thickLine(cx, ey - 6 * sc, cx + hw, ey + 5 * sc, 5 * sc, TEAL);
        break;
      }
      default: {
        drawEye(cx, ey, w, h, TEAL, BLUE, shape == NORMAL && lid < 0.2f);
        int top = ey - (int)h / 2;
        if (lid > 0.02f && h > 6) {                 // flat eyelid from the top
          cv->fillRect(cx - (int)w / 2 - 2, top - 1, (int)w + 4, (int)(h * lid) + 1, BLACK);
        }
        if (shape == HAPPY && h > 10) {
          // a big black circle eats the bottom -> smiling arch eyes, like  ^  ^
          int R = (int)(w * 0.52f);
          cv->fillCircle(cx, top + (int)(h * 0.34f) + R, R, BLACK);
          cv->fillRect(cx - (int)w / 2 - 2, top + (int)(h * 0.34f) + R, (int)w + 4, (int)h, BLACK);
        } else if (shape == SAD && h > 8) {
          // eyelid sloping down to the outside
          int x0 = cx - (int)w / 2 - 2, x1 = cx + (int)w / 2 + 2;
          if (i == 0) cv->fillTriangle(x0, top - 1, x1, top - 1, x0, top + (int)(h * 0.55f), BLACK);
          else cv->fillTriangle(x0, top - 1, x1, top - 1, x1, top + (int)(h * 0.55f), BLACK);
        }
      }
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

/** Defaults every frame; the scenes below change what they need. */
void resetFace() {
  tConverge = 0;
  tLid = 0;
  lookK = 0.18f;
}

// ---------------------------------------------------- follow the person ----
float distSmooth = -1, distSpeed = 0;   // cm, cm/s
uint32_t distT = 0;
float jx = 0, jy = 0;                   // tiny eye movements so it looks alive

void updateDistance(const SenseInfo &s, uint32_t now) {
  float dt = distT ? (now - distT) / 1000.f : 0;
  distT = now;
  if (!s.present) { distSmooth = -1; distSpeed = 0; return; }
  if (s.distCm <= 0) return;                         // one missed echo: keep the last value
  if (distSmooth < 0) { distSmooth = s.distCm; return; }
  float prev = distSmooth;
  distSmooth = approach(distSmooth, s.distCm, 0.3f);
  if (dt > 0) distSpeed = approach(distSpeed, (distSmooth - prev) / dt, 0.3f);
}

/** Eyes look at you: bigger and focused when you are close, small when far. */
float trackPerson(const SenseInfo &s, uint32_t now) {
  float d = constrain(distSmooth < 0 ? PRESENCE_CM / 2.f : distSmooth, 10.f, (float)PRESENCE_CM);
  float nearK = 1 - (d - 10) / (PRESENCE_CM - 10);        // 1 = close .. 0 = far
  if (now >= nextLook) {
    nextLook = now + random(500, 1700);
    jx = random(-2, 3);
    jy = random(-1, 2);
  }
  float x = (s.hasLR ? s.lookX * 15 : 0) + jx;
  float y = -1 - nearK * 5 + jy;                           // look up at your face when close
  float open = fabsf(distSpeed) > 35 ? 1.15f : 1.f;        // you move -> it pays attention
  setEyes(open, x, y);
  tConverge = nearK > 0.55f ? (nearK - 0.55f) * 12 : 0;    // cross a little when very close
  return 0.86f + nearK * 0.36f;                            // eye size
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

/** Talking mouth: opens on vowels of the word being shown. */
float mouthSmooth = 0;
void mouth(float level, uint32_t now, int cy, float maxH) {
  mouthSmooth = approach(mouthSmooth, level, 0.45f);
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

float mouthLevelFor(const char *s, int pos) {
  if (pos < 0 || !s[pos]) return 0.1f;
  char c = tolower(s[pos]);
  if (strchr("aeiouy", c)) return 0.95f;
  if (isalnum((unsigned char)c)) return 0.5f;
  return 0.12f;
}

// ------------------------------------------------------------ subtitles ----
// Karaoke style: words already read are white, the current word is
// highlighted, the next words are dim. Lines scroll up as it goes on.
const int SUB_TOP = 74, SUB_LINE = 13, SUB_LINES = 4, SUB_CHARS = 20;
float subScroll = 0;

struct Word { int16_t start, len, line, col; };

void subtitles(const char *s, int pos) {
  static Word words[160];
  int n = 0, line = 0, col = 0;
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

  // which word is it at?
  int cur = 0;
  for (int k = 0; k < n; k++) if (words[k].start <= pos) cur = k;
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

// ------------------------------------------------------- idle scenes -------
const uint32_t SLEEP_MS = 45000;        // nobody around this long -> falls asleep
const uint32_t SLEEP_NO_SENSOR_MS = 120000;

enum Trick { T_NONE, T_WINK, T_ROLL, T_HEARTS, T_WIGGLE, T_PEEK, T_SQUISH };
Trick trick = T_NONE;
uint32_t trickStart = 0, nextTrick = 0;
const uint32_t TRICK_LEN[] = {0, 600, 1200, 1700, 1300, 1700, 900};

void startTrick(const SenseInfo &s, uint32_t now) {
  int r = random(100);
  if (s.love >= 35 && r < 25) trick = T_HEARTS;
  else if (r < 45) trick = s.present || !s.ultraOk ? T_WINK : T_PEEK;
  else if (r < 62) trick = T_ROLL;
  else if (r < 78) trick = T_WIGGLE;
  else if (r < 90) trick = T_PEEK;
  else trick = T_SQUISH;
  trickStart = now;
  if (trick == T_WINK) {
    winkEye = random(2);
    winkStart = now;
    spawn(P_SPARK, winkEye ? 104 : 24, 34, 0, -8, 700, 4, now);
  }
  if (trick == T_HEARTS) spawn(P_HEART, 64, 84, 0, -26, 1500, 5, now);
}

/** Random little moves so the face never looks frozen. Returns the eye shape. */
Shape doTrick(uint32_t now, float &scale) {
  uint32_t tt = now - trickStart;
  switch (trick) {
    case T_ROLL: {                                   // eyes roll around in a circle
      float a = tt / (float)TRICK_LEN[T_ROLL] * 2 * PI;
      lookK = 0.5f;
      eyeL.tlx = eyeR.tlx = sinf(a) * 12;
      eyeL.tly = eyeR.tly = -cosf(a) * 7;
      return NORMAL;
    }
    case T_HEARTS: return HEART;
    case T_WIGGLE:                                   // happy wiggle side to side
      lookK = 0.45f;
      eyeL.tlx = eyeR.tlx = sinf(tt / 85.f) * 6;
      return HAPPY;
    case T_PEEK:                                     // looks left, then right
      lookK = 0.3f;
      eyeL.tlx = eyeR.tlx = tt < 800 ? -15 : 15;
      eyeL.tly = eyeR.tly = 2;
      return NORMAL;
    case T_SQUISH: {                                 // squash and pop like jelly
      float k = sinf(PI * tt / TRICK_LEN[T_SQUISH]);
      eyeL.tOpen = eyeR.tOpen = 1 - k * 0.45f;
      scale *= 1 + k * 0.12f;
      return NORMAL;
    }
    default: return NORMAL;
  }
}

void petScene(const FaceInfo &f, uint32_t now) {
  const SenseInfo &s = f.s;
  uint32_t pt = now - s.petSinceMs;
  static uint32_t lastHeart = 0;
  if (now - lastHeart > 260) {
    lastHeart = now;
    spawn(P_HEART, random(22, 107), random(70, 92), random(-8, 9), -random(30, 55), 1700, random(3, 7), now);
  }
  lookK = 0.4f;
  setEyes(1, sinf(now / 150.f) * 4, -2);             // nuzzles your hand
  drawEyes(pt > 3500 ? BLISS : HAPPY, now, 1.05f, 50);
  blush((int)eyeCy, eyeScale);
  const char *msg = pt < 1500 ? "hehe~" : pt < 3500 ? "that tickles!" : s.love >= 90 ? "best friends! <3" : "purrr... <3";
  centerText(msg, 99, PINK);
  loveBar(s.love, 113);
}

void afterPetScene(const FaceInfo &f, uint32_t now) {
  const SenseInfo &s = f.s;
  setEyes(1, 0, -fabsf(sinf(now / 200.f)) * 5);      // happy bounce
  lookK = 0.35f;
  drawEyes(HAPPY, now, 1.02f, 52);
  blush((int)eyeCy, eyeScale);
  centerText(s.pets % 3 == 0 ? "more pets?" : "thank you! <3", 99, PINK);
  loveBar(s.love, 113);
}

void tooCloseScene(uint32_t now) {
  setEyes(1, sinf(now / 35.f) * 2, 0);               // shaking
  lookK = 0.6f;
  drawEyes(SQUEEZE, now, 1.1f, 52);
  float p = fmodf(now / 1400.f, 1.f);                // sweat drop sliding down
  int sx = 112, sy = 26 + (int)(p * 14);
  uint16_t c = mix(BLUE, BLACK, p * p);
  cv->fillCircle(sx, sy + 3, 3, c);
  cv->fillTriangle(sx - 3, sy + 2, sx + 3, sy + 2, sx, sy - 4, c);
  centerText("too close!", 104, AMBER);
}

const char *GREETINGS[] = {"Hi!", "Hello!", "Hey!", "Hiya!"};

/** Someone arrived: (yawn if it was asleep) -> surprised -> happy "Hi!" */
bool greetScene(const FaceInfo &f, uint32_t now) {
  const SenseInfo &s = f.s;
  if (!s.arrivedMs || s.arrivedMs < f.modeSinceMs) return false;
  bool wake = s.awayBeforeMs > SLEEP_MS;
  uint32_t a = now - s.arrivedMs, wakeLen = wake ? 1700 : 0;
  if (a > wakeLen + 3200) return false;
  static uint32_t sparkedFor = 0;

  if (a < wakeLen) {                                 // waking up with a yawn
    float p = a / (float)wakeLen;
    tLid = 0.35f * (1 - p);
    setEyes(0.15f + p * 0.85f, 0, 4 - p * 5);
    drawEyes(NORMAL, now, 0.95f, 50);
    float m = sinf(PI * min(1.f, p * 1.25f));
    if (m > 0.05f) {
      int rw = 5 + (int)(6 * m), rh = 2 + (int)(9 * m);
      cv->fillRoundRect(64 - rw, 92 - rh, 2 * rw, 2 * rh, min(rw, rh), TEAL);
      if (rw > 3 && rh > 3) cv->fillRoundRect(64 - rw + 3, 92 - rh + 3, 2 * rw - 6, 2 * rh - 6, min(rw, rh) - 3, BLACK);
    }
    centerText("*yawn*", 112, DIM);
    return true;
  }
  uint32_t g = a - wakeLen;
  if (sparkedFor != s.arrivedMs) {                   // sparkle burst
    sparkedFor = s.arrivedMs;
    for (int k = 0; k < 6; k++) {
      float ang = k * PI / 3 + 0.4f;
      spawn(P_SPARK, 64 + cosf(ang) * 40, 56 + sinf(ang) * 30, cosf(ang) * 25, sinf(ang) * 20, 900, 4, now);
    }
  }
  if (g < 650) {                                     // surprised: wide eyes + "!"
    nextBlink = max(nextBlink, now + 700);           // no blinking while surprised
    setEyes(1.3f, 0, -3);
    lookK = 0.35f;
    drawEyes(NORMAL, now, 1.12f, 58);
    centerText("!", 13, AMBER, 2);
  } else {                                           // happy bounce + "Hi!"
    setEyes(1, s.hasLR ? s.lookX * 10 : 0, -fabsf(sinf(now / 170.f)) * 5);
    lookK = 0.35f;
    drawEyes(HAPPY, now, 1.05f, 60);
    bubble(GREETINGS[(s.arrivedMs / 7) % 4], 64, 13, 2, WHITE);
  }
  return true;
}

void idleFace(const FaceInfo &f, uint32_t now, uint32_t t) {
  const SenseInfo &s = f.s;
  statusBar(f);
  updateDistance(s, now);

  // 1) a hand on the IR sensor = petting
  if (s.petting) { trick = T_NONE; petScene(f, now); return; }
  if (s.pets && s.lastPetMs && now - s.lastPetMs < 3000) { afterPetScene(f, now); return; }
  // 2) too close!
  if (s.present && s.distCm > 0 && s.distCm < TOO_CLOSE_CM) { tooCloseScene(now); return; }
  // 3) someone just arrived
  if (greetScene(f, now)) { trick = T_NONE; return; }

  // 4) asleep when nobody is around
  uint32_t lastActive = max(f.modeSinceMs, s.lastPetMs);
  bool asleep = s.ultraOk ? (!s.present && now - max(lastActive, s.absentSinceMs) > SLEEP_MS)
                          : (now - lastActive > SLEEP_NO_SENSOR_MS);
  if (asleep) {
    trick = T_NONE;
    setEyes(0.14f, 0, 6 + sinf(now / 1300.f) * 2);   // slow breathing
    drawEyes(NORMAL, now, 0.95f);
    sleepExtras(now);
    centerText(f.note[0] ? f.note : f.ip, 119, f.note[0] ? AMBER : DIM);
    return;
  }

  // 5) awake: follow you, or look for you, plus random tricks
  float scale = 1;
  const char *line = nullptr;
  if (s.ultraOk && s.present) {
    scale = trackPerson(s, now);
  } else if (s.ultraOk) {
    uint32_t alone = now - max(s.absentSinceMs, f.modeSinceMs);
    eyeL.tOpen = eyeR.tOpen = 1;
    if (s.absentSinceMs > s.presentSinceMs && s.presentSinceMs && now - s.absentSinceMs < 3500 &&
        s.absentSinceMs - s.presentSinceMs > 3000) {
      lookK = 0.3f;
      lookAround(now, 1.4f);                         // where did you go?
      line = "where did you go?";
    } else {
      lookAround(now, 1.2f);
      if (alone > 20000) tLid = 0.38f;               // bored...
    }
    scale = 0.92f;
  } else {
    eyeL.tOpen = eyeR.tOpen = 1;
    lookAround(now);
  }

  if (!nextTrick) nextTrick = now + 5000;
  if (trick != T_NONE && now - trickStart > TRICK_LEN[trick]) {
    trick = T_NONE;
    nextTrick = now + random(6000, 14000);
    eyeL.tOpen = eyeR.tOpen = 1;
  }
  if (trick == T_NONE && now >= nextTrick && !line) startTrick(s, now);
  Shape shape = trick != T_NONE ? doTrick(now, scale) : NORMAL;
  if (trick != T_NONE) tLid = 0;

  drawEyes(shape, now, scale);
  if (line) centerText(line, 104, DIM);
  else if (!f.aiOnline) centerText("PC / Ollama offline", 104, AMBER);
  centerText(f.note[0] ? f.note : f.ip, 119, f.note[0] ? AMBER : DIM);
}

// --------------------------------------------------------------- frame -----
FaceMode lastMode = FACE_BOOT;

void frame(uint32_t now) {
  FaceInfo f = getFaceInfo();
  uint32_t t = now - f.modeSinceMs;
  cv->fillScreen(BLACK);
  resetFace();

  if (f.mode != lastMode) {
    lastMode = f.mode;
    nextLook = now + 400;
    trick = T_NONE;
    winkEye = -1;
  }
  // the eyes look at you in every mode (when the sensors know where you are)
  float lookX = f.s.present && f.s.hasLR ? f.s.lookX * 10 : 0;

  switch (f.mode) {
    case FACE_BOOT:
      bootScreen(t);
      break;

    case FACE_WIFI:
      wifiScreen(now);
      if (f.note[0]) centerText(f.note, 119, AMBER);
      break;

    case FACE_IDLE:
      idleFace(f, now, t);
      break;

    case FACE_LISTENING:
      statusBar(f);
      setEyes(1.15f, lookX, -2);
      if (f.heard[0]) {
        // you are talking: small eyes on top, your words appear live below
        drawEyes(NORMAL, now, 0.62f, 34);
        for (int k = 0; k < 2; k++) {
          float p = fmodf(t / 700.f + k / 2.f, 1.f);
          cv->drawCircle(64, 60, 3 + (int)(p * 7), mix(TEAL, BLACK, p));
        }
        cv->fillCircle(64, 60, 3, TEAL);
        subtitles(f.heard, (int)strlen(f.heard) - 1);   // newest word highlighted
      } else {
        drawEyes(NORMAL, now);
        listeningExtras(t);
      }
      break;

    case FACE_THINKING:
      statusBar(f);
      setEyes(0.72f, 9, -8);                          // looking up and to the side
      drawEyes(NORMAL, now);
      if (f.heard[0]) marquee(f.heard, 84, DIM, t);   // your question
      thinkingExtras(t);
      break;

    case FACE_SPEAKING:                               // showing the answer
      statusBar(f);
      setEyes(1, lookX * 0.6f, -2);
      if (f.subPos >= 0 && f.sub[0]) {
        drawEyes(HAPPY, now, 0.62f, 34);              // small eyes, room for subtitles
        mouth(mouthLevelFor(f.sub, f.subPos), now, 62, 9);
        subtitles(f.sub, f.subPos);
      } else {
        drawEyes(HAPPY, now);
        mouth(0.5f, now, 100, 18);
        marquee(f.text, 119, WHITE, t);
      }
      break;

    case FACE_ERROR:
      statusBar(f);
      setEyes(0.75f, 0, 4);
      drawEyes(SAD, now);
      marquee(f.text[0] ? f.text : "Something went wrong", 112, RED, t);
      break;
  }

  // petting while it is busy: hearts + blush on top of whatever it is doing
  if (f.s.petting && f.mode != FACE_IDLE && f.mode != FACE_BOOT && f.mode != FACE_WIFI) {
    static uint32_t lastHeart = 0;
    if (now - lastHeart > 400) {
      lastHeart = now;
      spawn(P_HEART, random(10, 118), random(40, 70), 0, -30, 1300, random(3, 5), now);
    }
    blush((int)eyeCy, eyeScale);
  }
  drawParts(now);
  flush();
}

void task(void *) {
  // SPI colour screen ~30 fps, I2C screens ~12 fps (one I2C frame takes ~45 ms).
  const uint32_t period = OLED_TYPE == 2 ? 33 : 80;
  for (;;) {
    uint32_t t0 = millis();
    frame(t0);
    uint32_t used = millis() - t0;
    // always sleep a little, so the other tasks on this core (WiFi, web server,
    // idle/watchdog) get time even if a frame was slow
    vTaskDelay(pdMS_TO_TICKS(used + 10 < period ? period - used : 10));
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
  PINK = rgb(255, 120, 170);

#if OLED_TYPE == 1
  Wire.begin(OLED_SDA, OLED_SCL);
  scr = new Adafruit_SSD1327(128, 128, &Wire, OLED_RST, 400000, 400000);
  ok = scr->begin(OLED_I2C_ADDR) || scr->begin(OLED_I2C_ADDR == 0x3D ? 0x3C : 0x3D);
#elif OLED_TYPE == 2
  SPI.begin(OLED_SCK, -1, OLED_MOSI, OLED_CS);
  scr = new Adafruit_SSD1351(128, 128, &SPI, OLED_CS, OLED_DC, OLED_RST);
  scr->begin(18000000);
  ok = true;   // SPI screens can't report if they are connected
#elif OLED_TYPE == 3
  Wire.begin(OLED_SDA, OLED_SCL);
  Wire.setClock(400000);
  scr = new Adafruit_SH1107(128, 128, &Wire, OLED_RST, 400000, 400000);   // SH1107 max is 400 kHz
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
