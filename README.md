<p align="center"><b>Made by Mostafa Mesbah Aabedi &amp; Sebghatullah Haidari</b></p>

# VoiceAI — ESP32 local AI assistant with a living face

Ask a question on your phone (type it or say it). The question goes to **Qwen 2.5 1.5B running in Ollama on your PC**. The answer appears in a cloud‑AI style chat app, the phone reads it out loud, and the ESP32's **OLED face shows it as karaoke subtitles**.

The face is alive:
- an **ultrasonic sensor** lets its eyes follow you;
- an **IR sensor** lets you **pet it**.

The app also shows token usage for every answer, like the usage pages of cloud AI services.

```
 ┌──────────────┐   WiFi / JSON    ┌──────────────┐   HTTP    ┌────────────────────┐
 │  Phone app   │ ───────────────▶ │    ESP32     │ ────────▶ │  PC: Ollama        │
 │ chat · mic   │ ◀─────────────── │ web API :80  │ ◀──────── │  qwen2.5:1.5b      │
 │ voice · usage│  reply + tokens  │              │           └────────────────────┘
 └──────────────┘                  └──┬────┬────┬─┘
                                      │    │    │
                          1.5" OLED ◀─┘    │    └─▶ IR sensor (petting)
                          (face)      HC-SR04 ultrasonic (eyes follow you)
```

## What's in the repo

| Folder | What it is |
|---|---|
| `firmware/` | ESP32 code (Arduino IDE **or** PlatformIO) |
| `android/` | Native Android app (Kotlin + Jetpack Compose) |
| `pc/` | Starts Ollama for the LAN |
| `.github/workflows/` | Builds the **APK** and the **firmware .bin** automatically on GitHub |

## Two ways to reach the AI (pick in Settings)

| Mode | Path | Good for |
|---|---|---|
| **Direct to PC** (default) | Phone → Ollama on the PC. The answer streams in live, then the phone sends it to the ESP32 screen. | Fastest: words appear as they are generated |
| **Through ESP32** | Phone → ESP32 → Ollama (like the original sketch). The ESP32 shows the answer. | Works even if only the ESP32 knows the PC address |

## The face

<p align="center"><img src="docs/oled-face-preview.gif" width="220" alt="OLED face animation"></p>

| What happens | What the face does |
|---|---|
| Nobody there | Looks around, gets bored (half‑closed eyes), then falls asleep with floating *z*s after 45 s |
| You walk up | Yawns if it was asleep, then opens its eyes wide **!**, sparkles, and a **"Hi!"** bubble with happy bouncing eyes |
| You move closer / farther | Eyes grow and focus (a little cross‑eyed) when you're close, and get smaller when you're far. They look up at your face. With **two** ultrasonic sensors they also follow you **left / right**. |
| You get closer than 8 cm | **> <** squeezed eyes, shaking, a sweat drop, *"too close!"* |
| You leave | *"where did you go?"* and it searches for you |
| **You pet it (IR sensor)** | Happy **^ ^** eyes, blush **///**, floating hearts, *"hehe~" → "that tickles!" → "purrr... <3"* and a **love meter**. Then *"thank you! <3"*. |
| Now and then | Winks, rolls its eyes, does a happy wiggle, peeks left and right, jelly squish. Shows **heart eyes** if you pet it a lot. |
| Listening | Your words appear live while you talk |
| Thinking | Eyes look up, dots orbit, your question scrolls |
| Answer | Smiling eyes, a talking mouth, and **karaoke subtitles** that follow the phone's voice |
| Error | Sad eyes + the message |

The status bar shows WiFi, AI online, how far you are (cm), and a heart that grows with love.

## App features

- Native Kotlin app with a chat screen like ChatGPT / Claude: chat history drawer, new chat, copy, regenerate, stop, light/dark theme
- Live streaming answers in Direct mode
- **Mic button** → full‑screen listening view with a voice‑reactive orb; auto‑send or edit first
- **Read aloud / Silent** chip above the text box. The phone voice moves the OLED subtitles along.
- **Show on ESP32 screen** button on every answer
- Per answer: **input · output tokens · tokens/second · time** (tap for model‑load time)
- **Usage page**:
  - total and today's tokens, input vs output split
  - tokens‑per‑answer and speed charts, context meter
  - ESP32 counters since boot, top chats, per‑model totals
- **Copy buttons on code blocks and quotes**, long‑press to select text, share answers
- **Personas**: Assistant, Teacher, Coder, Translator, Storyteller, Coach
- **Quick follow‑ups**: Simpler, Example, More detail, Summarize, In Dari
- **Hands‑free conversation**: the mic turns on again after each answer is read
- Chat list with **search, rename, share/export, delete**
- **Settings → ESP32 face**: live sensor view (distance, person, petting, pets, love meter) and subtitle speed

## 1. Wiring

### OLED (1.5" 128x128)

Pick your chip in `firmware/VoiceAI/config.h` → `OLED_TYPE`:

| `OLED_TYPE` | Chip | Colours | Pins | Wiring to ESP32 |
|---|---|---|---|---|
| `3` (default) | SH1107 | white | 4 pins, I2C (SDA SCL GND VCC) | VCC→3V3, GND→GND, SCL→GPIO22, SDA→GPIO21 |
| `1` | SSD1327 | 16 grays | 4 pins, I2C | same as type 3 |
| `2` | SSD1351 | full colour | 7 pins, SPI | VCC→3V3, GND→GND, DIN→GPIO23, CLK→GPIO18, CS→GPIO5, DC→GPIO16, RST→GPIO17 |
| `0` | – | – | – | no display |

If the screen shows random noise dots, the `OLED_TYPE` is wrong: try the other I2C type (1 or 3).

### Ultrasonic HC‑SR04 (eyes follow you)

```
HC-SR04 VCC  ──▶ ESP32 5V / VIN
HC-SR04 GND  ──▶ ESP32 GND
HC-SR04 TRIG ──▶ ESP32 GPIO26
HC-SR04 ECHO ──[ 1k ]──┬──▶ ESP32 GPIO27
                       │
                     [ 2k ]     (ECHO is 5 V, the ESP32 pin takes only 3.3 V)
                       │
                      GND
```
- The 3.3 V versions (**HC‑SR04P**, **RCWL‑1601**) can go to 3V3 and ECHO straight to GPIO27, with no resistors.
- Point it forward, at chest/face height, next to the screen.
- **Optional second sensor for left/right:**
  1. Wire it to TRIG GPIO32 and ECHO GPIO33 (same divider).
  2. Put sensor 1 on the **left** and sensor 2 on the **right** (as you look at the screen), about 10 cm apart.
  3. Set `ULTRA2_TRIG 32` and `ULTRA2_ECHO 33` in `config.h`.
  4. If the eyes look the wrong way, set `EYES_FLIP_X 1`.

### IR obstacle sensor (FC‑51 / TCRT5000) — pet the AI

```
IR VCC ──▶ ESP32 3V3
IR GND ──▶ ESP32 GND
IR OUT ──▶ ESP32 GPIO34
```
- Mount it on top of or beside the face, facing up or out. Wave or hold your hand 2–5 cm over it.
- Turn the small blue potentiometer on the module until its LED lights only when your hand is close.
- No IR sensor? Set `IR_PIN -1` in `config.h` (otherwise a floating pin can cause "ghost pets").

Other settings in `config.h`:
- `PRESENCE_CM` (120) — how near counts as "someone is here".
- `TOO_CLOSE_CM` (8) — closer than this triggers the "too close" face.

## 2. PC: Ollama on your network

1. Install Ollama from ollama.com, then run `ollama pull qwen2.5:1.5b`.
2. Ollama only listens to the PC itself by default. The ESP32 needs it on the network:
   - **Windows:** quit Ollama from the tray, then double‑click `pc/start_ollama_lan.bat` (it also prints your PC's IP).
   - Or set a user environment variable `OLLAMA_HOST=0.0.0.0` and restart Ollama.
   - **Linux:** run `sudo systemctl edit ollama`, add `Environment="OLLAMA_HOST=0.0.0.0"`, then restart.
3. Allow port **11434** in the firewall (Windows asks the first time — choose *Private networks*).
4. Test from your phone browser: `http://<PC-IP>:11434` should say *Ollama is running*.

## 3. ESP32 firmware

Edit `firmware/VoiceAI/config.h`: WiFi name/password, your PC's IP, sensor pins.

**Arduino IDE**
1. Boards Manager → install **esp32 by Espressif Systems 3.x**.
2. Library Manager → install these:
   - **ESPAsyncWebServer** (by ESP32Async)
   - **AsyncTCP** (by ESP32Async)
   - **ArduinoJson** (by Benoit Blanchon, v7)
   - **Adafruit GFX Library**
   - **Adafruit BusIO**
   - the library for your screen: **Adafruit SH110X**, **Adafruit SSD1327** or **Adafruit SSD1351 library**

   You don't need ESP8266Audio or ESP8266SAM any more.
3. Open `firmware/VoiceAI/VoiceAI.ino`, choose board **ESP32 Dev Module** and Partition scheme **Huge APP (3MB No OTA)**, then upload.

**PlatformIO**: `pio run -d firmware -t upload` (all libraries install automatically). Use `-e two_sensors` for the two‑sensor build.

**No compiler?** Every push builds the firmware on GitHub → *Actions → Build ESP32 firmware → VoiceAI-firmware*.

The Serial Monitor (115200) prints:
- the IP address;
- `[sense] person at 45 cm` and `[sense] pet #3` lines, so you can check the sensors.

## 4. Phone app (APK)

Direct download (always the newest build): **https://github.com/mostafaaabedia-tech/esp32-voice-ai/raw/apk/VoiceAI.apk**

First start:
1. Open **Settings**.
2. Type the **PC address** (e.g. `192.168.80.189`) and the **ESP32 address** (from the Serial Monitor).
3. Tap **Save & test connection**.

Voice input uses Android's speech service (Google app).

## ESP32 HTTP API

| Method & path | Body | Answer |
|---|---|---|
| `GET /api/info` | – | Device info, Ollama status, models, settings, token counters, plus `sensors{ultrasonic, two_sensors, distance_cm, person, ir, petting, pets, love}` |
| `POST /api/ask` | `{"messages":[{"role":"user","content":"Hi"}], "speak":true}` | `202 {"id":5}` (or `409` if busy). `speak` = show the answer on the OLED. |
| `GET /api/status` | – | `{"id","done_id","state":"idle/thinking/showing/error","reply","error","usage":{...}}` |
| `POST /api/speak` | `{"text":"hello"}` | Shows text on the OLED as subtitles |
| `POST /api/subpos` | `{"pos":120,"len":300}` or `{"done":true}` | Moves the subtitles with the phone's voice |
| `POST /api/stop` | – | Stops showing |
| `POST /api/face` | `{"state":"listening"}` (or `thinking`, `error`, `idle`, optional `"text"`) | Sets the OLED face |
| `POST /api/config` | Any of `ollama_host, ollama_port, model, system_prompt, max_words, num_predict, num_ctx, read_ms_per_char` | Saved in flash |
