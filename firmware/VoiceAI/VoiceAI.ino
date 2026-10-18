// ============================================================================
//  VoiceAI - ESP32 + PAM8406 local AI voice assistant
//
//  Phone app  --HTTP/JSON-->  ESP32  --HTTP-->  Ollama (qwen2.5:1.5b on your PC)
//                               |
//                               +--> internal DAC (GPIO25) --> PAM8406 --> speaker
//                               +--> 1.5" OLED: animated face (oled.h)
//
//  Board package : esp32 by Espressif, version 3.x (Arduino IDE) / pioarduino
//  Libraries     : ESP32Async/ESPAsyncWebServer, ESP32Async/AsyncTCP,
//                  bblanchon/ArduinoJson 7, earlephilhower/ESP8266Audio 2.x,
//                  earlephilhower/ESP8266SAM
// ============================================================================
#include <WiFi.h>
#include <esp_system.h>
#include <ESPmDNS.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <AsyncTCP.h>
#include <ESPAsyncWebServer.h>
#include <ArduinoJson.h>

#include <AudioFileSourceHTTPStream.h>
#include <AudioGeneratorWAV.h>
#include <ESP8266SAM.h>

#include "config.h"
#include "types.h"
#include "web_page.h"
#include "dac_out.h"
#include "oled.h"

#define FW_VERSION "2.4.0"
#define MAX_BODY_BYTES 16384

// loop() runs the AI request and the voice; give it more stack than the 8 KB default
SET_LOOP_TASK_STACK_SIZE(16 * 1024);

/** Why did the ESP32 (re)start? Shown on the OLED and in the app. */
const char *resetReasonText() {
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON:  return "";
    case ESP_RST_SW:       return "";
    case ESP_RST_EXT:      return "";
    case ESP_RST_BROWNOUT: return "power dip";
    case ESP_RST_PANIC:    return "crash";
    case ESP_RST_INT_WDT:
    case ESP_RST_TASK_WDT:
    case ESP_RST_WDT:      return "froze";
    default:               return "";
  }
}

// ---------------------------------------------------------------- settings --
struct Settings {
  String ollamaHost;
  uint16_t ollamaPort;
  String model;
  String systemPrompt;
  uint16_t maxWords;
  uint16_t numPredict;
  uint16_t numCtx;
  uint8_t voiceMode;   // 0 = SAM on device, 1 = PC TTS server
  uint16_t ttsPort;
  float volume;
} cfg;

Preferences prefs;

void loadSettings() {
  prefs.begin("voiceai", true);
  cfg.ollamaHost   = prefs.getString("host", DEFAULT_OLLAMA_HOST);
  cfg.ollamaPort   = prefs.getUShort("port", DEFAULT_OLLAMA_PORT);
  cfg.model        = prefs.getString("model", DEFAULT_MODEL);
  cfg.systemPrompt = prefs.getString("sys", DEFAULT_SYSTEM_PROMPT);
  cfg.maxWords     = prefs.getUShort("words", DEFAULT_MAX_WORDS);
  cfg.numPredict   = prefs.getUShort("npred", DEFAULT_NUM_PREDICT);
  cfg.numCtx       = prefs.getUShort("nctx2", DEFAULT_NUM_CTX);
  cfg.voiceMode    = prefs.getUChar("voice", DEFAULT_VOICE_MODE);
  cfg.ttsPort      = prefs.getUShort("ttsport", DEFAULT_TTS_PORT);
  cfg.volume       = prefs.getFloat("vol", DEFAULT_VOLUME);
  prefs.end();
}

void saveSettings() {
  prefs.begin("voiceai", false);
  prefs.putString("host", cfg.ollamaHost);
  prefs.putUShort("port", cfg.ollamaPort);
  prefs.putString("model", cfg.model);
  prefs.putString("sys", cfg.systemPrompt);
  prefs.putUShort("words", cfg.maxWords);
  prefs.putUShort("npred", cfg.numPredict);
  prefs.putUShort("nctx2", cfg.numCtx);
  prefs.putUChar("voice", cfg.voiceMode);
  prefs.putUShort("ttsport", cfg.ttsPort);
  prefs.putFloat("vol", cfg.volume);
  prefs.end();
}

// ------------------------------------------------------------------- state --
// The web server runs in its own task, so everything shared with loop()
// is guarded by this mutex.
SemaphoreHandle_t stateLock;
#define LOCK()   xSemaphoreTake(stateLock, portMAX_DELAY)
#define UNLOCK() xSemaphoreGive(stateLock)

const char *stateName(JobState s) {
  switch (s) {
    case ST_THINKING: return "thinking";
    case ST_SPEAKING: return "speaking";
    case ST_ERROR:    return "error";
    default:          return "idle";
  }
}

volatile JobState state = ST_IDLE;
uint32_t jobId = 0;            // increases on every /api/ask
uint32_t finishedJobId = 0;    // id of the last job that has a reply
String pendingBody;            // raw JSON of the request waiting for loop()
bool pendingSpeak = false;
String lastReply, lastError;
Usage lastUsage;

String speakQueue;             // text waiting to be spoken
volatile bool stopAudio = false;

// subtitles: what the speaker is saying right now (shown on the OLED)
String subText;                 // full spoken text (ASCII, as sent to the voice)
volatile int subChunkStart = 0; // robot voice: current phrase start / length / start time
volatile int subChunkLen = 0;
volatile uint32_t subChunkMs = 0;
volatile int subStreamPos = -1; // PC voice: position worked out from the audio stream
float samMsPerChar = 75;        // learned while speaking, keeps the words in sync

// what the OLED face shows
String screenText;              // reply / error text that scrolls on the OLED
String heardText;               // your words, live from the phone while you talk
FaceMode faceOverride = FACE_IDLE;  // set by the app (/api/face) e.g. listening
uint32_t faceOverrideUntil = 0;
FaceMode lastFaceMode = FACE_BOOT;
uint32_t faceSince = 0;
uint32_t bootMs = 0;

// lifetime counters (since boot)
uint32_t sessionPrompt = 0, sessionCompletion = 0, sessionRequests = 0;

// Ollama health, refreshed in loop() while idle
bool ollamaOnline = false;
String ollamaModels = "[]";    // JSON array of model names
uint32_t lastHealthCheck = 0;

// ------------------------------------------------------------------- audio --
DacOutput *dac = nullptr;
ESP8266SAM *sam = nullptr;
AudioGeneratorWAV *wav = nullptr;
AudioFileSourceHTTPStream *wavSrc = nullptr;

void ledSet(bool on) { digitalWrite(STATUS_LED_PIN, on ? HIGH : LOW); }

// Turn AI text into something a speech engine can read.
String cleanForSpeech(const String &in) {
  String out;
  out.reserve(in.length());
  for (size_t i = 0; i < in.length(); i++) {
    uint8_t c = in[i];
    if (c >= 0x80) {                       // skip UTF-8 multibyte (emoji etc.)
      while (i + 1 < in.length() && ((uint8_t)in[i + 1] & 0xC0) == 0x80) i++;
      out += ' ';
      continue;
    }
    if (c == '*' || c == '#' || c == '`' || c == '_' || c == '|' || c == '>' || c == '~') continue;
    if (c == '\n' || c == '\r' || c == '\t') { out += ". "; continue; }
    if (c == '&') { out += " and "; continue; }
    if (c == '%') { out += " percent"; continue; }
    if (c == '+') { out += " plus "; continue; }
    if (c == '=') { out += " equals "; continue; }
    out += (char)c;
  }
  // collapse repeated spaces / dots
  while (out.indexOf("  ") >= 0) out.replace("  ", " ");
  while (out.indexOf(". .") >= 0) out.replace(". .", ".");
  out.trim();
  return out;
}

/**
 * SAM only knows letters, digits and simple punctuation. Any other character
 * makes it fail AND leak ~4 KB of memory (a bug in the library) - after a few
 * answers the ESP32 would run out of memory and restart. Replace them with
 * spaces (same length, so the subtitle positions stay correct).
 */
String samSafe(const String &in) {
  String out = in;
  for (size_t i = 0; i < out.length(); i++) {
    char c = out[i];
    bool ok = isalnum((unsigned char)c) || c == ' ' || c == '.' || c == ',' || c == '?' ||
              c == '!' || c == '\'' || c == ';' || c == ':' || c == '-';
    if (!ok) out.setCharAt(i, ' ');
  }
  return out;
}

// SAM speaks short phrases of 3-5 words: long phrases can overflow its
// internal buffers, and short ones keep the subtitle highlight in sync.
void sayWithSam(const String &text) {
  const size_t MAXC = 32;
  size_t start = 0;
  while (start < text.length() && !stopAudio) {
    size_t end = min(text.length(), start + MAXC);
    if (end < text.length()) {
      int cut = -1;
      for (int i = end; i > (int)start + 8; i--) {        // prefer to stop at punctuation
        char ch = text[i - 1];
        if (ch == '.' || ch == '!' || ch == '?' || ch == ',' || ch == ';' || ch == ':') { cut = i; break; }
      }
      if (cut < 0) for (int i = end; i > (int)start; i--) if (text[i] == ' ') { cut = i; break; }
      if (cut > 0) end = cut;
    }
    String part = text.substring(start, end);
    int lead = 0;
    while (lead < (int)part.length() && part[lead] == ' ') lead++;
    part.trim();
    bool hasLetters = false;
    for (size_t i = 0; i < part.length(); i++) if (isalnum((unsigned char)part[i])) { hasLetters = true; break; }
    if (hasLetters) {
      if (ESP.getFreeHeap() < 24000) {                     // never speak into an out-of-memory crash
        Serial.printf("[sam] low memory (%u), skipping speech\n", ESP.getFreeHeap());
        break;
      }
      subChunkStart = start + lead;
      subChunkLen = part.length();
      subChunkMs = millis();
      if (!sam->Say(dac, samSafe(part).c_str())) Serial.println("[sam] could not say: " + part);
      uint32_t took = millis() - subChunkMs;
      if (took > 150 && !stopAudio) {                      // learn the real speed
        float m = (float)took / part.length();
        samMsPerChar = constrain(samMsPerChar * 0.6f + m * 0.4f, 30.f, 200.f);
      }
    }
    start = end;
  }
  subChunkLen = 0;
  dac->stop();
}

String urlEncode(const String &s) {
  static const char *hex = "0123456789ABCDEF";
  String e;
  e.reserve(s.length() * 3);
  for (size_t i = 0; i < s.length(); i++) {
    uint8_t c = (uint8_t)s[i];               // unsigned: no sprintf overflow on UTF-8
    if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') e += (char)c;
    else if (c == ' ') e += '+';
    else { e += '%'; e += hex[c >> 4]; e += hex[c & 15]; }
  }
  return e;
}

bool startPcTts(const String &text) {
  String url = "http://" + cfg.ollamaHost + ":" + String(cfg.ttsPort) + "/tts?text=" + urlEncode(text);
  wavSrc = new AudioFileSourceHTTPStream(url.c_str());
  wav = new AudioGeneratorWAV();
  if (wav->begin(wavSrc, dac)) return true;
  Serial.println("[tts] PC TTS server not reachable, falling back to SAM");
  delete wav; wav = nullptr;
  delete wavSrc; wavSrc = nullptr;
  return false;
}

void finishPcTts() {
  if (wav) { wav->stop(); delete wav; wav = nullptr; }
  if (wavSrc) { delete wavSrc; wavSrc = nullptr; }   // fixes the leak in v1
  dac->stop();
}

// ------------------------------------------------------------------ ollama --
void checkOllama() {
  HTTPClient http;
  http.setConnectTimeout(1500);
  http.setTimeout(2000);
  String url = "http://" + cfg.ollamaHost + ":" + String(cfg.ollamaPort) + "/api/tags";
  bool ok = false;
  String models = "[]";
  if (http.begin(url)) {
    int code = http.GET();
    if (code == 200) {
      ok = true;
      JsonDocument filter;
      filter["models"][0]["name"] = true;
      JsonDocument doc;
      if (!deserializeJson(doc, http.getStream(), DeserializationOption::Filter(filter))) {
        JsonDocument arr;
        JsonArray a = arr.to<JsonArray>();
        for (JsonObject m : doc["models"].as<JsonArray>()) a.add(m["name"].as<const char *>());
        models = "";
        serializeJson(arr, models);
      }
    }
    http.end();
  }
  LOCK();
  ollamaOnline = ok;
  ollamaModels = models;
  UNLOCK();
}

// Runs the request that the phone sent. Body format:
// { "messages":[{"role":"user","content":"..."}...], "speak":true }
bool runChat(const String &body, String &reply, Usage &u, String &err) {
  JsonDocument in;
  if (deserializeJson(in, body)) { err = "Bad JSON from app"; return false; }

  JsonDocument req;
  req["model"] = in["model"].is<const char *>() ? in["model"].as<String>() : cfg.model;
  req["stream"] = false;
  req["keep_alive"] = "30m";
  JsonObject opt = req["options"].to<JsonObject>();
  opt["num_predict"] = cfg.numPredict;
  // Only send a context size if one is set. Changing it makes Ollama restart
  // its engine (slow), so by default we use the model's own setting like v1.
  if (cfg.numCtx > 0) opt["num_ctx"] = cfg.numCtx;
  opt["temperature"] = in["temperature"] | 0.7;

  JsonArray msgs = req["messages"].to<JsonArray>();
  JsonObject sys = msgs.add<JsonObject>();
  sys["role"] = "system";
  sys["content"] = cfg.systemPrompt + " Keep every answer under " + String(cfg.maxWords) + " words.";

  JsonArray history = in["messages"].as<JsonArray>();
  if (history.isNull() || history.size() == 0) { err = "No messages"; return false; }
  for (JsonObject m : history) {
    const char *role = m["role"] | "user";
    if (strcmp(role, "user") && strcmp(role, "assistant") && strcmp(role, "system")) continue;  // app may add a persona as "system"
    JsonObject o = msgs.add<JsonObject>();
    o["role"] = role;
    o["content"] = m["content"] | "";
  }

  String payload;
  serializeJson(req, payload);
  in.clear();
  req.clear();

  HTTPClient http;
  http.setConnectTimeout(4000);
  http.setTimeout(OLLAMA_TIMEOUT_MS);      // v1 used the 5 s default and timed out (max 65535 ms)
  String url = "http://" + cfg.ollamaHost + ":" + String(cfg.ollamaPort) + "/api/chat";
  if (!http.begin(url)) { err = "Bad Ollama URL"; return false; }
  http.addHeader("Content-Type", "application/json");

  uint32_t t0 = millis();
  int code = http.POST(payload);
  if (code != 200) {
    err = code < 0 ? "Can't reach Ollama at " + cfg.ollamaHost + " (" + http.errorToString(code) + ")"
                   : "Ollama HTTP " + String(code) + ": " + http.getString().substring(0, 120);
    http.end();
    return false;
  }

  JsonDocument res;
  DeserializationError de = deserializeJson(res, http.getStream());
  http.end();
  if (de) { err = String("Bad reply from Ollama: ") + de.c_str(); return false; }
  if (res["error"].is<const char *>()) { err = res["error"].as<String>(); return false; }

  reply = res["message"]["content"].as<String>();
  reply.trim();
  u.promptTokens     = res["prompt_eval_count"] | 0;
  u.completionTokens = res["eval_count"] | 0;
  uint64_t evalNs    = res["eval_duration"] | (uint64_t)0;
  u.genMs    = evalNs / 1000000ULL;
  u.promptMs = (res["prompt_eval_duration"] | (uint64_t)0) / 1000000ULL;
  u.loadMs   = (res["load_duration"] | (uint64_t)0) / 1000000ULL;
  u.totalMs  = millis() - t0;
  u.tokensPerSec = evalNs ? u.completionTokens * 1e9f / (float)evalNs : 0;
  return true;
}

// ------------------------------------------------------------- OLED face --
FaceInfo getFaceInfo() {
  FaceInfo f;
  uint32_t now = millis();
  f.wifiOk = WiFi.status() == WL_CONNECTED;
  f.rssi = f.wifiOk ? WiFi.RSSI() : 0;
  if (f.wifiOk) strlcpy(f.ip, WiFi.localIP().toString().c_str(), sizeof f.ip);
  f.audioLevel = dac ? dac->level() : 0;
  if (now < 60000 && resetReasonText()[0]) snprintf(f.note, sizeof f.note, "restarted: %s", resetReasonText());

  FaceMode m;
  static JobState prevState = ST_IDLE;
  static uint32_t errorAt = 0;
  LOCK();
  if (state == ST_ERROR && prevState != ST_ERROR) errorAt = now;
  prevState = state;
  f.aiOnline = ollamaOnline;
  if (now - bootMs < 2600) m = FACE_BOOT;
  else if (!f.wifiOk) m = FACE_WIFI;
  else if (state == ST_THINKING) m = FACE_THINKING;
  else if (state == ST_SPEAKING) m = FACE_SPEAKING;
  else if (faceOverrideUntil && (int32_t)(faceOverrideUntil - now) > 0) m = faceOverride;
  else if (state == ST_ERROR && now - errorAt < 8000) m = FACE_ERROR;   // sad face for 8 s
  else m = FACE_IDLE;
  if (m != lastFaceMode) { lastFaceMode = m; faceSince = now; }
  f.mode = m;
  f.modeSinceMs = faceSince;
  strlcpy(f.text, screenText.c_str(), sizeof f.text);
  if (m == FACE_LISTENING || m == FACE_THINKING) strlcpy(f.heard, heardText.c_str(), sizeof f.heard);
  if (m == FACE_SPEAKING && subText.length()) {
    strlcpy(f.sub, subText.c_str(), sizeof f.sub);
    if (subStreamPos >= 0) f.subPos = subStreamPos;
    else if (subChunkLen > 0) {
      int done = (int)((now - subChunkMs) / samMsPerChar);
      f.subPos = subChunkStart + constrain(done, 0, subChunkLen - 1);
    } else f.subPos = 0;
    f.subPos = constrain(f.subPos, 0, (int)strlen(f.sub) - 1);
  }
  UNLOCK();
  return f;
}

/** OLED can only draw plain ASCII: drop emojis / other scripts. */
String asciiOnly(const String &in) {
  String out;
  out.reserve(in.length());
  for (size_t i = 0; i < in.length(); i++) {
    uint8_t c = in[i];
    if (c >= 0x80) {
      while (i + 1 < in.length() && ((uint8_t)in[i + 1] & 0xC0) == 0x80) i++;
      continue;
    }
    if (c == '\n' || c == '\r' || c == '\t') c = ' ';
    if (c == '*' || c == '#' || c == '`') continue;
    out += (char)c;
  }
  out.trim();
  return out;
}

// ------------------------------------------------------------- web server --
AsyncWebServer server(80);

// Collects a POST body into request->_tempObject (freed by the library).
void collectBody(AsyncWebServerRequest *r, uint8_t *data, size_t len, size_t index, size_t total) {
  if (total > MAX_BODY_BYTES) return;
  if (index == 0) {
    r->_tempObject = malloc(total + 1);
    if (!r->_tempObject) return;
  }
  if (!r->_tempObject) return;
  memcpy((uint8_t *)r->_tempObject + index, data, len);
  if (index + len == total) ((char *)r->_tempObject)[total] = 0;
}

String bodyOf(AsyncWebServerRequest *r) {
  return r->_tempObject ? String((const char *)r->_tempObject) : String();
}

void sendJson(AsyncWebServerRequest *r, int code, JsonDocument &doc) {
  String out;
  serializeJson(doc, out);
  r->send(code, "application/json", out);
}

void sendError(AsyncWebServerRequest *r, int code, const char *msg) {
  JsonDocument d;
  d["error"] = msg;
  sendJson(r, code, d);
}

void fillUsage(JsonObject o, const Usage &u) {
  o["prompt_tokens"] = u.promptTokens;
  o["completion_tokens"] = u.completionTokens;
  o["total_tokens"] = u.promptTokens + u.completionTokens;
  o["tokens_per_sec"] = serialized(String(u.tokensPerSec, 1));
  o["total_ms"] = u.totalMs;
  o["load_ms"] = u.loadMs;
  o["prompt_ms"] = u.promptMs;
  o["gen_ms"] = u.genMs;
  o["num_ctx"] = cfg.numCtx;
}

void setupRoutes() {
  DefaultHeaders::Instance().addHeader("Access-Control-Allow-Origin", "*");
  DefaultHeaders::Instance().addHeader("Access-Control-Allow-Headers", "Content-Type");
  DefaultHeaders::Instance().addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");

  server.on("/", HTTP_GET, [](AsyncWebServerRequest *r) {
    r->send(200, "text/html", WEB_PAGE);
  });

  // Device + AI info for the app header / device panel
  server.on("/api/info", HTTP_GET, [](AsyncWebServerRequest *r) {
    JsonDocument d;
    LOCK();
    d["name"] = DEVICE_HOSTNAME;
    d["fw"] = FW_VERSION;
    d["ip"] = WiFi.localIP().toString();
    d["rssi"] = WiFi.RSSI();
    d["heap"] = ESP.getFreeHeap();
    d["min_heap"] = ESP.getMinFreeHeap();
    d["reset"] = resetReasonText();
    d["uptime_s"] = millis() / 1000;
    d["state"] = stateName(state);
    d["ollama_online"] = ollamaOnline;
    d["models"] = serialized(ollamaModels);
    JsonObject c = d["config"].to<JsonObject>();
    c["ollama_host"] = cfg.ollamaHost;
    c["ollama_port"] = cfg.ollamaPort;
    c["model"] = cfg.model;
    c["system_prompt"] = cfg.systemPrompt;
    c["max_words"] = cfg.maxWords;
    c["num_predict"] = cfg.numPredict;
    c["num_ctx"] = cfg.numCtx;
    c["voice_mode"] = cfg.voiceMode;
    c["tts_port"] = cfg.ttsPort;
    c["volume"] = cfg.volume;
    JsonObject s = d["session"].to<JsonObject>();
    s["requests"] = sessionRequests;
    s["prompt_tokens"] = sessionPrompt;
    s["completion_tokens"] = sessionCompletion;
    UNLOCK();
    sendJson(r, 200, d);
  });

  // Ask the AI. Returns immediately with a job id; poll /api/status.
  server.on("/api/ask", HTTP_POST, [](AsyncWebServerRequest *r) {
    String body = bodyOf(r);
    if (body.isEmpty()) return sendError(r, 400, "Empty or too large body");
    JsonDocument peek;
    if (deserializeJson(peek, body)) return sendError(r, 400, "Invalid JSON");
    LOCK();
    if (state == ST_THINKING) { UNLOCK(); return sendError(r, 409, "Busy, still thinking"); }
    stopAudio = true;                       // a new question interrupts speech
    faceOverrideUntil = 0;                  // stop showing "listening"
    {
      JsonArray ms = peek["messages"].as<JsonArray>();
      if (!ms.isNull() && ms.size()) heardText = asciiOnly(ms[ms.size() - 1]["content"] | "").substring(0, 190);
    }
    pendingBody = body;
    pendingSpeak = peek["speak"] | true;
    uint32_t id = ++jobId;
    state = ST_THINKING;
    lastReply = ""; lastError = "";
    UNLOCK();
    JsonDocument d;
    d["id"] = id;
    sendJson(r, 202, d);
  }, nullptr, collectBody);

  server.on("/api/status", HTTP_GET, [](AsyncWebServerRequest *r) {
    JsonDocument d;
    LOCK();
    d["id"] = jobId;
    d["done_id"] = finishedJobId;
    d["state"] = stateName(state);
    d["reply"] = lastReply;
    if (lastError.length()) d["error"] = lastError;
    fillUsage(d["usage"].to<JsonObject>(), lastUsage);
    UNLOCK();
    sendJson(r, 200, d);
  });

  // Speak any text on the speaker: {"text":"hello"}
  server.on("/api/speak", HTTP_POST, [](AsyncWebServerRequest *r) {
    JsonDocument in;
    if (deserializeJson(in, bodyOf(r)) || !in["text"].is<const char *>()) return sendError(r, 400, "Need {text}");
    LOCK();
    if (state == ST_THINKING) { UNLOCK(); return sendError(r, 409, "Busy"); }
    stopAudio = true;
    speakQueue = in["text"].as<String>();
    screenText = asciiOnly(speakQueue).substring(0, 150);
    faceOverrideUntil = 0;
    UNLOCK();
    r->send(200, "application/json", "{\"ok\":true}");
  }, nullptr, collectBody);

  // The app tells the face what it is doing: {"state":"listening|thinking|error|idle","text":"..."}
  server.on("/api/face", HTTP_POST, [](AsyncWebServerRequest *r) {
    JsonDocument in;
    if (deserializeJson(in, bodyOf(r))) return sendError(r, 400, "Invalid JSON");
    String st = in["state"] | "idle";
    LOCK();
    uint32_t now = millis();
    bool hasText = in["text"].is<const char *>();
    String txt = hasText ? asciiOnly(in["text"].as<String>()) : String();
    if (st == "listening") {
      // the phone sends this again with every new word, so a short timeout is enough
      faceOverride = FACE_LISTENING; faceOverrideUntil = now + 8000;
      heardText = hasText ? txt.substring(txt.length() > 190 ? txt.length() - 190 : 0) : String();
    } else if (st == "thinking") {
      faceOverride = FACE_THINKING; faceOverrideUntil = now + 120000;
      if (hasText) heardText = txt.substring(0, 190);
    } else if (st == "error") {
      faceOverride = FACE_ERROR; faceOverrideUntil = now + 8000;
      if (hasText) screenText = txt.substring(0, 150);
    } else {
      faceOverrideUntil = 0;                       // idle: back to the normal face
    }
    UNLOCK();
    r->send(200, "application/json", "{\"ok\":true}");
  }, nullptr, collectBody);

  server.on("/api/stop", HTTP_POST, [](AsyncWebServerRequest *r) {
    LOCK();
    stopAudio = true;
    speakQueue = "";
    UNLOCK();
    r->send(200, "application/json", "{\"ok\":true}");
  });

  // Change settings: any subset of the keys shown in /api/info -> config
  server.on("/api/config", HTTP_POST, [](AsyncWebServerRequest *r) {
    JsonDocument in;
    if (deserializeJson(in, bodyOf(r))) return sendError(r, 400, "Invalid JSON");
    LOCK();
    if (in["ollama_host"].is<const char *>()) cfg.ollamaHost = in["ollama_host"].as<String>();
    if (in["ollama_port"].is<int>())  cfg.ollamaPort = in["ollama_port"];
    if (in["model"].is<const char *>()) cfg.model = in["model"].as<String>();
    if (in["system_prompt"].is<const char *>()) cfg.systemPrompt = in["system_prompt"].as<String>();
    if (in["max_words"].is<int>())    cfg.maxWords = constrain(in["max_words"].as<int>(), 5, 300);
    if (in["num_predict"].is<int>())  cfg.numPredict = constrain(in["num_predict"].as<int>(), 16, 1024);
    if (in["num_ctx"].is<int>())      cfg.numCtx = (in["num_ctx"].as<int>() <= 0 ? 0 : constrain(in["num_ctx"].as<int>(), 512, 32768));
    if (in["voice_mode"].is<int>())   cfg.voiceMode = in["voice_mode"].as<int>() ? 1 : 0;
    if (in["tts_port"].is<int>())     cfg.ttsPort = in["tts_port"];
    if (in["volume"].is<float>())     cfg.volume = constrain(in["volume"].as<float>(), 0.0f, 4.0f);
    saveSettings();
    if (dac) dac->SetGain(cfg.volume);
    lastHealthCheck = 0;                    // re-check Ollama with the new host
    UNLOCK();
    r->send(200, "application/json", "{\"ok\":true}");
  }, nullptr, collectBody);

  server.onNotFound([](AsyncWebServerRequest *r) {
    if (r->method() == HTTP_OPTIONS) r->send(204);   // CORS preflight
    else r->send(404, "application/json", "{\"error\":\"not found\"}");
  });
}

// ------------------------------------------------------------------- setup --
void connectWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.setHostname(DEVICE_HOSTNAME);
  WiFi.setSleep(false);                      // faster replies, no audio dropouts
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("[wifi] connecting");
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED) {
    ledSet((millis() / 150) % 2);
    delay(150);
    if (millis() - t0 > 30000) { Serial.println("\n[wifi] failed, rebooting"); ESP.restart(); }
  }
  ledSet(false);
  Serial.printf("\n[wifi] connected, IP %s  RSSI %d dBm\n", WiFi.localIP().toString().c_str(), WiFi.RSSI());
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n=== VoiceAI " FW_VERSION " ===");
  if (resetReasonText()[0]) Serial.printf("[boot] last restart was caused by: %s\n", resetReasonText());
  pinMode(STATUS_LED_PIN, OUTPUT);
  stateLock = xSemaphoreCreateMutex();
  bootMs = millis();
  loadSettings();

  dac = new DacOutput();
  oledBegin();                 // boot animation runs while WiFi connects
  dac->SetGain(cfg.volume);
  sam = new ESP8266SAM();
  sam->SetVoice(ESP8266SAM::VOICE_SAM);

  connectWiFi();
  if (MDNS.begin(DEVICE_HOSTNAME)) MDNS.addService("http", "tcp", 80);

  setupRoutes();
  server.begin();
  Serial.printf("[web] open http://%s or the VoiceAI app\n", WiFi.localIP().toString().c_str());

  checkOllama();
  Serial.printf("[ollama] %s at %s:%u\n", ollamaOnline ? "online" : "OFFLINE", cfg.ollamaHost.c_str(), cfg.ollamaPort);
  speakQueue = "Voice assistant ready.";
}

// -------------------------------------------------------------------- loop --
void speakNow(const String &raw) {
  String text = cleanForSpeech(raw);
  if (text.isEmpty()) return;
  LOCK();
  state = ST_SPEAKING; stopAudio = false;
  faceOverrideUntil = 0;
  subText = text.substring(0, 700);
  subChunkLen = 0; subStreamPos = -1;
  UNLOCK();
  ledSet(true);
  if (cfg.voiceMode == 1 && startPcTts(text)) return;   // continues in loop()
  sayWithSam(text);
  ledSet(false);
  LOCK(); if (state == ST_SPEAKING) state = ST_IDLE; UNLOCK();
}

void loop() {
  // 1) keep a PC-TTS stream playing
  if (wav) {
    uint32_t sz = wavSrc ? wavSrc->getSize() : 0;
    if (sz > 44) {                                   // subtitle follows the audio stream
      uint32_t pos = wavSrc->getPos();
      subStreamPos = (int)((uint64_t)(pos > 44 ? pos - 44 : 0) * subText.length() / (sz - 44));
    }
    if (stopAudio || !wav->isRunning() || !wav->loop()) {
      finishPcTts();
      ledSet(false);
      LOCK(); if (state == ST_SPEAKING) state = ST_IDLE; UNLOCK();
    }
    return;
  }

  // 2) a question from the app?
  LOCK();
  bool haveJob = state == ST_THINKING && pendingBody.length();
  String body = haveJob ? pendingBody : String();
  bool speak = pendingSpeak;
  uint32_t myId = jobId;
  pendingBody = "";
  UNLOCK();

  if (haveJob) {
    Serial.println("[ask] " + body.substring(0, 200));
    String reply, err;
    Usage u;
    ledSet(true);
    bool ok = runChat(body, reply, u, err);
    ledSet(false);
    LOCK();
    if (myId == jobId) {                       // ignore if a newer job replaced it
      finishedJobId = myId;
      sessionRequests++;
      if (ok) {
        lastReply = reply; lastUsage = u; lastError = "";
        faceOverrideUntil = 0;
        screenText = asciiOnly(reply).substring(0, 150);
        sessionPrompt += u.promptTokens;
        sessionCompletion += u.completionTokens;
        state = speak ? ST_SPEAKING : ST_IDLE;
      } else {
        lastError = err; lastReply = ""; lastUsage = Usage();
        faceOverrideUntil = 0;
        screenText = asciiOnly(err).substring(0, 150);
        state = ST_ERROR;
      }
    }
    UNLOCK();
    if (ok) {
      Serial.printf("[ai] %s\n[usage] in %u, out %u, %.1f tok/s, %u ms\n", reply.c_str(),
                    u.promptTokens, u.completionTokens, u.tokensPerSec, u.totalMs);
      if (speak) speakNow(reply);
    } else {
      Serial.println("[error] " + err);
      if (speak) speakNow("Sorry, I could not reach the A I.");
      LOCK(); if (state != ST_THINKING) state = ST_ERROR; UNLOCK();
    }
    return;
  }

  // 3) text queued by /api/speak
  LOCK();
  String say = speakQueue;
  speakQueue = "";
  UNLOCK();
  if (say.length()) { speakNow(say); return; }

  // 4) housekeeping while idle
  if (millis() - lastHealthCheck > 15000 || lastHealthCheck == 0) {
    lastHealthCheck = millis();
    if (WiFi.status() != WL_CONNECTED) {
      Serial.println("[wifi] lost, reconnecting");
      WiFi.reconnect();
    } else {
      checkOllama();
    }
  }
  delay(5);
}
