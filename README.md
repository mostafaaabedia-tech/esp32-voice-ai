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

## Works offline

The app needs **no internet**:
- **Listening:** speech recognition is built into the app (sherpa-onnx streaming Zipformer, English).
- **Speaking:** a built-in voice pack with **8 voices** (KittenTTS). You can change pitch and speed in *Settings → Your AI companion*.
- **Thinking:** the AI brain is Ollama on your PC, reached over your own WiFi.
- **Without the PC:** spells, timers, the time and date, fortunes, jokes, dice and coin flips all still work on the phone.

The built-in voice and listening are English. For other languages the app falls back to the phone's own speech service.

APKs:
- [`VoiceAI.apk`](https://github.com/mostafaaabedia-tech/esp32-voice-ai/raw/apk/VoiceAI.apk) for most phones (64‑bit).
- [`VoiceAI-32bit.apk`](https://github.com/mostafaaabedia-tech/esp32-voice-ai/raw/apk/VoiceAI-32bit.apk) for older phones.

## The wizard

The default companion is **Zephyr**, an original young apprentice wizard. He has a starry hat, a robe, a wand with a glowing star, and **Pip**, a little floating light. **Nova** can still be chosen in Settings.

- **8 spells** on the phone and the OLED: Starburst, Float, Rainbow, Snowfall, Fireworks, Bubbles, Vanish and Dream dust. Tap ✨ Spells, or say "cast a spell" / "show me some magic". The AI also casts them by itself when it fits.
- **Petting** (IR sensor or rubbing his hat on the screen): the hat squashes, stars fly, and he says one of his own magical lines.
- **Quick buttons:** 🔮 Fortune, ⏳ Timer (an hourglass countdown on the OLED and "TIME'S UP!"), 😄 Joke, 🎲 Dice, 🪙 Coin.
- **OLED wizard theme:**
  - a hat on the eyes;
  - a crescent‑moon boot screen;
  - spell animations with a waving wand;
  - an hourglass timer.

  Switching the character in the app also switches the OLED theme.

## Your AI companion (app home screen)

The app opens on **Nova**, an original anime‑style AI companion. You can rename her and change her hair and eye colours. She lives in your phone and in the ESP32:

- **She talks with you.** Tap the mic and speak. She answers with her voice and her face, and the OLED shows her words. With **talk mode** on, the mic opens again after she answers, so you can just keep talking.
- **She's alive on screen.** She blinks, breathes, lip‑syncs her words and shows 14 expressions: happy, love (heart eyes), shy, excited, surprised, sad (tear), angry (anger mark), sleepy, thinking, laugh, wink and more. Her eyes follow your finger, and with two ultrasonic sensors they follow *you*.
- **Touch her.**
  - Rub her head → a hand pats her, she closes her eyes, blushes and hearts float up. The OLED blushes too.
  - Poke her cheek → she pouts.
  - Tap her → "huh? what's up?"
- **She reacts to the real world.**
  - IR pets → she says something cute.
  - You walk up → she greets you (and says she missed you after a long time away).
  - You get too close or walk away → she says something about it.
- **She decides by herself.** Every few minutes (*Quiet / Normal / Chatty*) she decides whether to start a conversation. She might ask about your day, share a fun fact, remind you to drink water, or stay quiet. The screen shows what she decided.
- **She has feelings.** Every reply starts with a feeling she picks herself, and it shows on both faces. Her love level, the time of day and how long nobody talked to her change her mood.
- **She remembers you.** When you tell her about yourself she saves it, and she learns your name. You can see and delete what she remembers in *Settings → Your AI companion*.
- **Make her your own.** Pick her personality (*Cheerful, Shy, Teasing, Calm*), voice pitch, speed and voice.

The plain text chat (with token usage) is still there: tap the chat icon on her screen.

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

### Move the model to another laptop (no internet needed)

1. On the old PC, double‑click `pc/export_model.bat`. It copies only `qwen2.5:1.5b` (~1 GB) into `pc/ollama-export`.
2. Copy the whole `pc` folder to a USB stick. Put the Ollama installer on it too if the new laptop has no internet.
3. On the new laptop:
   1. Install Ollama.
   2. Right‑click `pc/import_model.bat` → **Run as administrator**.
   3. The script copies the model into Ollama and sets `OLLAMA_HOST=0.0.0.0:11434` and `OLLAMA_KEEP_ALIVE=30m`. It also opens port 11434 in the firewall, warns you if the WiFi is set to *Public*, and prints the laptop's IP.
4. Put the new IP in the app (Settings → PC address → Save & test). Saving also updates the ESP32.

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
| `POST /api/speak` | `{"text":"hello","emotion":"happy"}` | Shows text on the OLED as subtitles, with eyes for the feeling |
| `POST /api/subpos` | `{"pos":120,"len":300}` or `{"done":true}` | Moves the subtitles with the phone's voice |
| `GET /api/sense?since=N` | – | Sensors + events newer than N: `pet`, `pet_end`, `arrive`, `leave`, `too_close` |
| `POST /api/stop` | – | Stops showing |
| `POST /api/face` | `{"state":"listening"}` (or `thinking`, `error`, `idle`, optional `"text"`), or `{"state":"emotion","emotion":"love","ms":4000}` | Sets the OLED face |
| `POST /api/config` | Any of `ollama_host, ollama_port, model, system_prompt, max_words, num_predict, num_ctx, read_ms_per_char` | Saved in flash |
