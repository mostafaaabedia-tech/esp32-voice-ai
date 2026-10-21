// ============================================================================
//  Sensors for the VoiceAI face
//
//  Ultrasonic HC-SR04 (1 or 2)  -> how far you are (and left / right with 2),
//                                  so the eyes follow you, greet you, fall asleep
//                                  when you leave and squint if you come too close
//  IR obstacle sensor (FC-51)   -> hold or wave your hand over it to pet the AI
//
//  Everything runs in its own small task, so a slow AI answer never stops the
//  sensors. The echo pulse is timed with interrupts (no busy waiting).
// ============================================================================
#pragma once
#include "config.h"
#include "types.h"

namespace sense {

portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;
SenseInfo info;                     // guarded by mux, copied out with get()

// Things that happened, so the phone app can react (the AI companion talks
// when you pet it or walk up). Read with /api/sense?since=<seq>.
enum EventType : uint8_t { EV_NONE, EV_PET, EV_PET_END, EV_ARRIVE, EV_LEAVE, EV_TOO_CLOSE };
struct SenseEvent { uint32_t seq, ms; EventType type; };
const int EVQ = 16;
SenseEvent evq[EVQ];
uint32_t evSeq = 0;

// call with mux held
void pushEvent(EventType t, uint32_t now) {
  evSeq++;
  evq[evSeq % EVQ] = {evSeq, now, t};
}

const char *eventName(EventType t) {
  switch (t) {
    case EV_PET: return "pet";
    case EV_PET_END: return "pet_end";
    case EV_ARRIVE: return "arrive";
    case EV_LEAVE: return "leave";
    case EV_TOO_CLOSE: return "too_close";
    default: return "none";
  }
}

struct Echo {
  int trig = -1, pin = -1;
  volatile uint32_t rise = 0, width = 0;
  volatile bool got = false, seen = false;
  int hist[5] = {999, 999, 999, 999, 999};
  int hi = 0;
};
Echo e1, e2;

void ARDUINO_ISR_ATTR echoIsr(void *arg) {
  Echo *e = (Echo *)arg;
  uint32_t t = micros();
  if (digitalRead(e->pin)) {
    e->rise = t;
    e->seen = true;                 // proves a sensor is really connected
  } else if (e->rise) {
    e->width = t - e->rise;
    e->rise = 0;
    e->got = true;
  }
}

void setupEcho(Echo &e, int trig, int echo) {
  e.trig = trig;
  e.pin = echo;
  if (trig < 0 || echo < 0) return;
  pinMode(trig, OUTPUT);
  digitalWrite(trig, LOW);
  pinMode(echo, INPUT);
  attachInterruptArg(digitalPinToInterrupt(echo), echoIsr, &e, CHANGE);
}

// IR is checked every 10 ms, also while we wait for an echo
bool irRaw() {
#if IR_PIN >= 0
  return digitalRead(IR_PIN) == (IR_ACTIVE_LOW ? LOW : HIGH);
#else
  return false;
#endif
}

int irCount = 0;
uint32_t irActiveSince = 0;
bool irStuck = false;

void pollIr(uint32_t now) {
#if IR_PIN >= 0
  bool raw = irRaw();
  irCount = raw ? min(irCount + 1, 3) : max(irCount - 1, 0);
  bool was = info.petting;
  bool on = was ? irCount > 0 : irCount >= 3;        // ~30 ms debounce
  if (raw && !irActiveSince) irActiveSince = now;
  if (!raw) { irActiveSince = 0; irStuck = false; }
  // stuck on for 30 s (sensor pointed at a wall / not connected) -> ignore it
  if (irActiveSince && now - irActiveSince > 30000) { irStuck = true; on = false; }
  if (irStuck) on = false;

  static uint32_t lastLove = 0, lastDecay = 0;
  portENTER_CRITICAL(&mux);
  if (on && !was) {
    info.petting = true;
    info.petSinceMs = now;
    info.pets++;
    info.love = min(100, info.love + 8);
    lastLove = now;
    pushEvent(EV_PET, now);
  } else if (!on && was) {
    info.petting = false;
    info.lastPetMs = now;
    pushEvent(EV_PET_END, now);
  }
  if (info.petting && now - lastLove > 700) {        // holding your hand there = more love
    lastLove = now;
    info.love = min(100, info.love + 2);
  }
  if (!info.petting && info.love && now - lastDecay > 6000) {   // slowly misses you
    lastDecay = now;
    info.love--;
  }
  portEXIT_CRITICAL(&mux);
  if (on && !was) Serial.printf("[sense] pet #%u, love %u%%\n", info.pets, info.love);
#endif
}

void waitPolling(uint32_t ms) {
  for (uint32_t t = 0; t < ms; t += 10) {
    vTaskDelay(pdMS_TO_TICKS(10));
    pollIr(millis());
  }
}

/** One measurement in cm, -1 = nothing in range. */
int measure(Echo &e) {
  if (e.trig < 0) return -1;
  e.got = false;
  e.rise = 0;
  digitalWrite(e.trig, LOW);
  delayMicroseconds(3);
  digitalWrite(e.trig, HIGH);
  delayMicroseconds(10);
  digitalWrite(e.trig, LOW);
  waitPolling(40);                                   // an echo from 4 m takes ~24 ms
  if (!e.got) return -1;
  uint32_t w = e.width;
  if (w > 18000) return -1;                          // > ~3 m = nobody
  int cm = w / 58;
  return cm < 2 ? -1 : cm;
}

/** Median of the last 5 readings: one bad echo can't make the eyes jump. */
int filtered(Echo &e, int raw) {
  e.hist[e.hi] = raw < 0 ? 999 : raw;
  e.hi = (e.hi + 1) % 5;
  int s[5];
  memcpy(s, e.hist, sizeof s);
  for (int i = 1; i < 5; i++)
    for (int j = i; j > 0 && s[j] < s[j - 1]; j--) { int t = s[j]; s[j] = s[j - 1]; s[j - 1] = t; }
  return s[2] >= 999 ? -1 : s[2];
}

void task(void *) {
  int nearCount = 0;
  uint32_t farSince = 0;
  bool closeArmed = true;
  float lx = 0;
  info.absentSinceMs = millis();
  for (;;) {
    int d1 = filtered(e1, measure(e1));
    int d2 = -1;
    if (e2.trig >= 0) {
      waitPolling(15);                               // let the first ping die out
      d2 = filtered(e2, measure(e2));
    }
    waitPolling(20);
    uint32_t now = millis();

    int d = d1 < 0 ? d2 : (d2 < 0 ? d1 : min(d1, d2));
    bool near = d > 0 && d < PRESENCE_CM;
    bool hasLR = e2.trig >= 0 && e2.seen;

    // left / right: the closer sensor is the side you stand on
    float tx = 0;
    if (hasLR) {
      bool n1 = d1 > 0 && d1 < PRESENCE_CM, n2 = d2 > 0 && d2 < PRESENCE_CM;
      if (n1 && n2) tx = constrain((d1 - d2) / 18.f, -1.f, 1.f);
      else if (n1) tx = -0.85f;
      else if (n2) tx = 0.85f;
      if (EYES_FLIP_X) tx = -tx;
    }
    lx = lx * 0.55f + tx * 0.45f;

    portENTER_CRITICAL(&mux);
    info.ultraOk = e1.seen || e2.seen;
    info.hasLR = hasLR;
    info.dist1Cm = d1;
    info.dist2Cm = d2;
    info.distCm = d;
    info.lookX = lx;
    bool arrived = false, left = false;
    if (near) {
      farSince = 0;
      if (++nearCount >= 2 && !info.present) {
        info.present = true;
        info.presentSinceMs = now;
        info.awayBeforeMs = info.absentSinceMs ? now - info.absentSinceMs : 600000;
        if (info.awayBeforeMs > 4000) {               // say hi only after a real absence
          info.arrivedMs = now;
          pushEvent(EV_ARRIVE, now);
        }
        arrived = true;
      }
    } else {
      nearCount = 0;
      if (!farSince) farSince = now;
      if (info.present && now - farSince > 1500) {     // gone for 1.5 s = you left
        info.present = false;
        info.absentSinceMs = now;
        left = true;
        pushEvent(EV_LEAVE, now);
      }
    }
    if (info.present && d > 0 && d < TOO_CLOSE_CM && closeArmed) { closeArmed = false; pushEvent(EV_TOO_CLOSE, now); }
    if (d < 0 || d > TOO_CLOSE_CM + 6) closeArmed = true;
    portEXIT_CRITICAL(&mux);
    if (arrived) Serial.printf("[sense] person at %d cm\n", d);
    if (left) Serial.println("[sense] person left");
  }
}

/** Events newer than `since` (oldest first). Returns how many were copied. */
int eventsSince(uint32_t since, SenseEvent *out, int max, uint32_t &seqNow) {
  portENTER_CRITICAL(&mux);
  seqNow = evSeq;
  uint32_t from = since + 1;
  if (evSeq >= EVQ && from < evSeq - EVQ + 1) from = evSeq - EVQ + 1;
  int n = 0;
  for (uint32_t s = from; s <= evSeq && n < max; s++) out[n++] = evq[s % EVQ];
  portEXIT_CRITICAL(&mux);
  return n;
}

/** Copy of the latest sensor values (safe from any task). */
SenseInfo get() {
  portENTER_CRITICAL(&mux);
  SenseInfo s = info;
  portEXIT_CRITICAL(&mux);
  return s;
}

}  // namespace sense

inline void sensorsBegin() {
  using namespace sense;
#if IR_PIN >= 0
  pinMode(IR_PIN, INPUT);          // GPIO 34-39 have no pull-up; the IR module has its own
  info.irOk = true;
#endif
  setupEcho(e1, ULTRA_TRIG, ULTRA_ECHO);
  setupEcho(e2, ULTRA2_TRIG, ULTRA2_ECHO);
  if (ULTRA_TRIG < 0 && ULTRA2_TRIG < 0 && IR_PIN < 0) return;
  xTaskCreatePinnedToCore(sense::task, "sense", 3072, nullptr, 2, nullptr, 1);
  Serial.printf("[sense] ultrasonic on %d/%d%s, IR on %d\n", ULTRA_TRIG, ULTRA_ECHO,
                ULTRA2_TRIG >= 0 ? " + second sensor" : "", IR_PIN);
}
