<p align="center">
  <img src="docs/logo.png" width="140" alt="Afghan-Turk Maarif Schools Herat Boys HS">
</p>

<p align="center"><b>Made by Mostafa Mesbah Aabedi &amp; Sebghatullah Haidari</b><br>
Afghan-Turk Maarif Schools · Herat Boys HS</p>

# VoiceAI — ESP32 + PAM8406 local AI voice assistant

Ask a question on your phone (type it or say it), the ESP32 sends it to **Qwen 2.5 1.5B running in Ollama on your PC**, and the answer is shown in a cloud‑AI style chat app **and spoken through a PAM8406 speaker**. The app also shows token usage for every answer, like the usage pages of cloud AI services.

```
 ┌──────────────┐   WiFi / JSON    ┌──────────────┐   HTTP    ┌────────────────────┐
 │  Phone app   │ ───────────────▶ │    ESP32     │ ────────▶ │  PC: Ollama        │
 │ chat · mic   │ ◀─────────────── │ web API :80  │ ◀──────── │  qwen2.5:1.5b      │
 │ token usage  │  reply + tokens  │  SAM voice   │           │  (+ tts_server.py) │
 └──────────────┘                  └──────┬───────┘           └────────────────────┘
                                          │ GPIO25 (DAC)
                                    ┌─────▼─────┐
                                    │  PAM8406  │──▶ 🔊 speaker
                                    └───────────┘
```

## What's in the repo

| Folder | What it is |
|---|---|
| `firmware/` | ESP32 code (Arduino IDE **or** PlatformIO) |
| `android/` | Native Android app (Kotlin + Jetpack Compose) |
| `pc/` | Helpers for the PC: start Ollama for the LAN, optional nicer voice server |
| `.github/workflows/` | Builds the **APK** and the **firmware .bin** automatically on GitHub |

## Two ways to reach the AI (pick in Settings)

| Mode | Path | Good for |
|---|---|---|
| **Direct to PC** (default) | Phone → Ollama on the PC, answer streams in live → phone sends the text to the ESP32 to speak | Fastest, words appear as they are generated |
| **Through ESP32** | Phone → ESP32 → Ollama (like the original sketch) → ESP32 speaks | Works even if only the ESP32 knows the PC address |

## App features

- Native Kotlin app, chat screen like ChatGPT / Claude: chat history drawer, new chat, copy, regenerate, stop, light/dark theme
- Live streaming answers in Direct mode
- **Mic button** → full-screen listening view with a voice-reactive orb, auto-send or edit first
- **Where answers play**: ESP32 speaker, phone, both, or silent (chip above the text box)
- Per answer: **input · output tokens · tokens/second · time** (tap for model-load time)
- Context-window bar under the text box
- **Usage page**: total / today tokens, input vs output split, tokens-per-answer chart, speed chart, context meter, ESP32 counters since boot, top chats, per-model totals
- **Copy buttons on code blocks and quotes**, long-press to select any text, share answers
- **Personas**: Assistant, Teacher, Coder, Translator, Storyteller, Coach (one tap above the text box)
- **Quick follow-ups** under the last answer: Simpler, Example, More detail, Summarize, In Dari
- **Hands-free conversation**: the mic turns on again after each answer is spoken
- Tap your own message to **copy, edit & resend, or ask again**
- Chat list with **search, rename, share/export, delete**
- **Creativity** slider (temperature) and **text size** setting
- **Settings**: mode, PC + ESP32 addresses with live status, model picker (your installed Ollama models), system prompt, max words / tokens, chat memory, speaker volume, robot/PC voice, speech language

## 1. Wiring (ESP32 → PAM8406)

```
ESP32 GPIO25 ──[ 1k ]──┬──[ 10µF + ]──▶ PAM8406  L‑IN   (or R‑IN)
                       │
                     [ 1k ]            (divider: DAC is 3.3 V, too loud for the amp input)
                       │
ESP32 GND ─────────────┴──────────────▶ PAM8406  GND (input ground)
ESP32 5V / VIN ───────────────────────▶ PAM8406  VCC (+5 V)
ESP32 GND ────────────────────────────▶ PAM8406  GND (power)
                                          PAM8406  L+ / L‑ ──▶ speaker 4–8 Ω, 3–5 W
```

Tips
- **Never connect a speaker wire to GND.** The PAM8406 outputs (L+ / L‑) are bridged; join the speaker only between L+ and L‑.
- Power the amp from **5 V** (USB 5 V / VIN pin), not 3.3 V. A 1000 µF capacitor across the amp's VCC/GND stops resets and crackle at high volume.
- If it's still too loud or distorted, lower **Speaker volume** in the app, or use a 10k potentiometer instead of the 1k/1k divider.
- Many PAM8406 boards have a **MODE** pin/jumper: Class D (default) is fine; switch to Class AB only if an AM radio nearby buzzes.
- The ESP32 DAC is 8‑bit, so expect "walkie‑talkie" quality. That's normal.

## 1b. OLED face (1.5" 128x128, optional)

<p align="center"><img src="docs/oled-face-preview.gif" width="200" alt="OLED face animation"></p>

The ESP32 shows an animated robot face: boot logo with credits → WiFi spinner → idle eyes that blink, look around and fall asleep after 2 min (and the ESP32's **IP address** at the bottom) → **listening** rings when you tap the mic → **thinking** eyes + orbiting dots → **speaking** smiling eyes, a mouth that moves with the real speaker sound, and **karaoke subtitles synced with the voice** (the word being spoken is highlighted, lines scroll as it talks) → **sad** eyes with the error message.

Pick your chip in `firmware/VoiceAI/config.h` → `OLED_TYPE`:

| `OLED_TYPE` | Chip | Colours | Pins | Wiring to ESP32 |
|---|---|---|---|---|
| `1` | SSD1327 | 16 grays | 4 pins, I2C | VCC→3V3, GND→GND, SCL→GPIO22, SDA→GPIO21 |
| `2` | SSD1351 | full colour | 7 pins, SPI | VCC→3V3, GND→GND, DIN→GPIO23, CLK→GPIO18, CS→GPIO5, DC→GPIO16, RST→GPIO17 |
| `3` (default) | SH1107 | white | 4 pins, I2C (SDA SCL GND VCC) | same as type 1 |
| `0` | – | – | – | no display |

Arduino IDE libraries to add: **Adafruit GFX Library**, **Adafruit BusIO**, and the one for your chip: **Adafruit SSD1327**, **Adafruit SSD1351 library** or **Adafruit SH110X**.

If the Serial Monitor says `display not found`, check SDA/SCL. If the screen shows random noise dots, the `OLED_TYPE` is wrong: try the other I2C type (1 or 3).

## 2. PC: Ollama on your network

1. Install Ollama from ollama.com, then `ollama pull qwen2.5:1.5b`.
2. Ollama only listens to the PC itself by default. The ESP32 needs it on the network:
   - **Windows:** quit Ollama from the tray, then double‑click `pc/start_ollama_lan.bat` (it also prints your PC's IP).
   - Or set a user environment variable `OLLAMA_HOST=0.0.0.0` and restart Ollama.
   - **Linux:** `sudo systemctl edit ollama` → add `Environment="OLLAMA_HOST=0.0.0.0"` → restart.
3. Allow port **11434** in the firewall (Windows asks the first time — choose *Private networks*).
4. Test from your phone browser: `http://<PC-IP>:11434` should say *Ollama is running*.
5. Give the PC a fixed IP in your router if you can, so it doesn't change.

### Optional: nicer voice
The ESP32 has a built‑in robot voice (SAM) that works with no extra software. For a human‑like voice:
```
pip install pyttsx3
python pc/tts_server.py
```
Then in the app: Settings → Voice → **PC voice**. If the PC voice server is off, the ESP32 falls back to the robot voice automatically.

## 3. ESP32 firmware

Edit `firmware/VoiceAI/config.h`: WiFi name/password and your PC's IP.

**Arduino IDE**
1. Boards Manager → install **esp32 by Espressif Systems 3.x**.
2. Library Manager → install: **ESPAsyncWebServer** (by ESP32Async), **AsyncTCP** (by ESP32Async), **ArduinoJson** (by Benoit Blanchon, v7), **ESP8266Audio** (by Earle Philhower, v2.x), **ESP8266SAM** (by Earle Philhower), plus the OLED libraries from section 1b.
3. Open `firmware/VoiceAI/VoiceAI.ino`, board **ESP32 Dev Module**, Partition scheme **Huge APP (3MB No OTA)**, upload.

**PlatformIO**: `pio run -d firmware -t upload` (all libraries install automatically).

**No compiler?** Every push builds the firmware on GitHub → *Actions → Build ESP32 firmware → VoiceAI-firmware*.

Open the Serial Monitor at 115200 — it prints the ESP32's IP and says "Voice assistant ready" on the speaker.

## 4. Phone app (APK)

Direct download (always the newest build): **https://github.com/mostafaaabedia-tech/esp32-voice-ai/raw/apk/VoiceAI.apk**
or *Actions → Build Android APK → Artifacts → VoiceAI-apk*.

First start: Settings → type the **PC address** (e.g. `192.168.80.189`) and the **ESP32 address** (from the Serial Monitor) → **Save & test connection**.

Voice input uses Android's speech service (Google app). For offline voice typing, download the offline speech pack for your language in the Google app settings.

Build it yourself: open the `android/` folder in Android Studio and press Run.

## ESP32 HTTP API

| Method & path | Body | Answer |
|---|---|---|
| `GET /api/info` | – | device info, Ollama status, installed models, settings, token counters since boot |
| `POST /api/ask` | `{"messages":[{"role":"user","content":"Hi"}], "speak":true}` | `202 {"id":5}` (or `409` if busy) |
| `GET /api/status` | – | `{"id","done_id","state":"idle/thinking/speaking/error","reply","error","usage":{prompt_tokens, completion_tokens, total_tokens, tokens_per_sec, total_ms, load_ms, prompt_ms, gen_ms, num_ctx}}` |
| `POST /api/speak` | `{"text":"hello"}` | speaks any text |
| `POST /api/stop` | – | stops speech |
| `POST /api/face` | `{"state":"listening"}` (or `thinking`, `error`, `idle`, optional `"text"`) | sets the OLED face |
| `POST /api/config` | any of `ollama_host, ollama_port, model, system_prompt, max_words, num_predict, num_ctx, voice_mode, tts_port, volume` | saved in flash |

## What was fixed from the first version

| Problem in v1 | Fix |
|---|---|
| `AudioOutputI2S(0, InternalDAC)` — in ESP8266Audio 2.x the DAC mode is ignored, so **no sound** reached the PAM8406 | Own `DacOutput` class on GPIO25 with working volume and correct sample rate |
| Google Translate TTS URL is `http://` — Google redirects to https, which the stream class can't play, and it needs internet | Offline SAM voice on the ESP32 + optional offline PC voice |
| `/getreply` built JSON by hand → any `"` or new line in the answer broke the app | All JSON made with ArduinoJson |
| Firmware forced `num_ctx`, which made Ollama restart its engine (slow, and fails if the install is broken) | Context size is only sent if you set one |
| `HTTPClient` default 5 s timeout → first answer (model loading) always failed | 65 s timeout, model kept loaded for 30 min |
| App gave up after 15 s | Waits up to 150 s with a live timer |
| `urlEncode` used `sprintf("%02X")` on signed chars → buffer overflow / crash on non‑English text | Safe unsigned encoder |
| `new AudioFileSourceHTTPStream` every answer, never deleted → memory leak, reboot after a few questions | Freed after each answer |
| Shared Strings used from two tasks with no lock | Mutex around shared state |
| No memory: every question started fresh | App sends the recent conversation (configurable) |
| No reconnect if WiFi drops | Auto‑reconnect + health check every 15 s |
| Web Speech API in the page — Chrome blocks the mic on `http://` | Native Kotlin app with Android speech recognition |
