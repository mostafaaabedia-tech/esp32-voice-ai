// ============================================================================
//  WiFi for VoiceAI: saved networks + a setup hotspot
//
//  - Up to 5 networks are saved in the ESP32 (set them from the phone app,
//    Settings -> Network). The one from config.h is always tried too.
//  - At start it joins the strongest known network that is around.
//  - If none works, it makes its own WiFi "VoiceAI-Setup" (password
//    voiceai123). Join it with the phone, open the app -> Network -> Setup,
//    pick your WiFi, done. While in setup mode it keeps retrying the known
//    networks in the background (when nobody is connected to the hotspot).
// ============================================================================
#pragma once
#include <WiFi.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include "config.h"

#define SETUP_AP_SSID "VoiceAI-Setup"
#define SETUP_AP_PASS "voiceai123"
#define MAX_SAVED_WIFI 5

namespace netw {

struct Net { String ssid, pass; };
Net saved[MAX_SAVED_WIFI];
int nSaved = 0;
volatile bool apMode = false;
String trying;                 // network being joined (shown on the OLED)
uint32_t restartAt = 0;        // restart to use a new network
uint32_t lastRetry = 0;

void load() {
  Preferences p;
  p.begin("wifi", true);
  String js = p.getString("list", "[]");
  p.end();
  JsonDocument d;
  nSaved = 0;
  if (deserializeJson(d, js)) return;
  for (JsonObject o : d.as<JsonArray>()) {
    if (nSaved >= MAX_SAVED_WIFI) break;
    saved[nSaved].ssid = o["s"] | "";
    saved[nSaved].pass = o["p"] | "";
    if (saved[nSaved].ssid.length()) nSaved++;
  }
}

void store() {
  JsonDocument d;
  JsonArray a = d.to<JsonArray>();
  for (int i = 0; i < nSaved; i++) {
    JsonObject o = a.add<JsonObject>();
    o["s"] = saved[i].ssid;
    o["p"] = saved[i].pass;
  }
  String js;
  serializeJson(d, js);
  Preferences p;
  p.begin("wifi", false);
  p.putString("list", js);
  p.end();
}

bool forget(const String &ssid) {
  for (int i = 0; i < nSaved; i++) {
    if (saved[i].ssid != ssid) continue;
    for (int j = i; j < nSaved - 1; j++) saved[j] = saved[j + 1];
    nSaved--;
    store();
    return true;
  }
  return false;
}

/** Save a network as the first one to try. */
void add(const String &ssid, const String &pass) {
  forget(ssid);
  if (nSaved == MAX_SAVED_WIFI) nSaved--;            // drop the oldest
  for (int j = nSaved; j > 0; j--) saved[j] = saved[j - 1];
  saved[0] = {ssid, pass};
  nSaved++;
  store();
}

bool tryOne(const String &ssid, const String &pass, uint32_t ms) {
  trying = ssid;
  Serial.printf("[wifi] trying %s\n", ssid.c_str());
  WiFi.begin(ssid.c_str(), pass.c_str());
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < ms) delay(150);
  if (WiFi.status() == WL_CONNECTED) return true;
  WiFi.disconnect();
  delay(100);
  return false;
}

void startAP() {
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(SETUP_AP_SSID, SETUP_AP_PASS);
  apMode = true;
  trying = "";
  Serial.printf("[wifi] no known network. Setup hotspot: '%s' password '%s' -> app -> Network -> Setup (192.168.4.1)\n",
                SETUP_AP_SSID, SETUP_AP_PASS);
}

/** Join the strongest known network that is around, else start the setup hotspot. */
void begin() {
  load();
  WiFi.mode(WIFI_STA);
  WiFi.setHostname(DEVICE_HOSTNAME);
  WiFi.setSleep(false);                          // faster replies
  WiFi.setAutoReconnect(true);

  // known networks: saved ones + the one in config.h
  Net cand[MAX_SAVED_WIFI + 1];
  int nc = 0;
  for (int i = 0; i < nSaved; i++) cand[nc++] = saved[i];
  bool cfgKnown = false;
  for (int i = 0; i < nc; i++) if (cand[i].ssid == WIFI_SSID) cfgKnown = true;
  if (!cfgKnown && strlen(WIFI_SSID)) cand[nc++] = {WIFI_SSID, WIFI_PASSWORD};

  // which of them are around, and how strong?
  int rssi[MAX_SAVED_WIFI + 1];
  for (int i = 0; i < nc; i++) rssi[i] = -999;
  int found = WiFi.scanNetworks();
  for (int j = 0; j < found; j++)
    for (int i = 0; i < nc; i++)
      if (WiFi.SSID(j) == cand[i].ssid && WiFi.RSSI(j) > rssi[i]) rssi[i] = WiFi.RSSI(j);
  WiFi.scanDelete();

  // strongest first; networks not seen (maybe hidden) last, with a shorter wait
  for (int pass = 0; pass < 2; pass++) {
    bool used[MAX_SAVED_WIFI + 1] = {false};
    for (int k = 0; k < nc; k++) {
      int best = -1;
      for (int i = 0; i < nc; i++)
        if (!used[i] && (best < 0 || rssi[i] > rssi[best])) best = i;
      used[best] = true;
      bool visible = rssi[best] > -999;
      if ((pass == 0) != visible) continue;
      if (tryOne(cand[best].ssid, cand[best].pass, visible ? 15000 : 7000)) {
        trying = "";
        Serial.printf("[wifi] connected to %s, IP %s, RSSI %d dBm\n", cand[best].ssid.c_str(),
                      WiFi.localIP().toString().c_str(), WiFi.RSSI());
        return;
      }
    }
  }
  startAP();
}

/** Call from loop(): restart for a new network, retry known networks while in setup mode. */
void tick() {
  if (restartAt && (int32_t)(millis() - restartAt) > 0) ESP.restart();
  if (!apMode) return;
  if (WiFi.status() == WL_CONNECTED) {                 // a known network came back
    Serial.printf("[wifi] connected to %s, closing the setup hotspot\n", WiFi.SSID().c_str());
    WiFi.softAPdisconnect(true);
    WiFi.mode(WIFI_STA);
    apMode = false;
    return;
  }
  // nobody on the hotspot: quietly try the first known network again every 90 s
  if (millis() - lastRetry > 90000 && WiFi.softAPgetStationNum() == 0) {
    lastRetry = millis();
    String s = nSaved ? saved[0].ssid : String(WIFI_SSID);
    String p = nSaved ? saved[0].pass : String(WIFI_PASSWORD);
    if (s.length()) WiFi.begin(s.c_str(), p.c_str());
  }
}

}  // namespace netw
