"""
VoiceAI - optional PC voice server
==================================
Gives the ESP32 a nicer, human-like voice than the built-in robot voice.
The ESP32 asks   http://<this-pc>:5050/tts?text=Hello   and gets back a WAV file
that it streams to the PAM8406.

Works offline. Uses the voices installed on your PC:
  Windows : built-in SAPI voices (via pyttsx3)
  Linux   : espeak-ng (sudo apt install espeak-ng)  or pyttsx3
  macOS   : pyttsx3 (NSSpeechSynthesizer)

Setup:
    pip install pyttsx3
    python tts_server.py
Then in the phone app: Settings -> Voice -> ESP32 voice -> "PC voice".
"""
import os
import shutil
import subprocess
import tempfile
import threading
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(os.environ.get("TTS_PORT", "5050"))
RATE = int(os.environ.get("TTS_RATE", "175"))      # speaking speed (words/min)
VOICE = os.environ.get("TTS_VOICE", "")            # part of a voice name, e.g. "Zira" or "David"
MAX_CHARS = 600
lock = threading.Lock()                            # TTS engines are not thread safe


def synth_pyttsx3(text: str, path: str) -> None:
    import pyttsx3
    engine = pyttsx3.init()
    engine.setProperty("rate", RATE)
    if VOICE:
        for v in engine.getProperty("voices"):
            if VOICE.lower() in (v.name or "").lower():
                engine.setProperty("voice", v.id)
                break
    engine.save_to_file(text, path)
    engine.runAndWait()
    engine.stop()


def synth_espeak(text: str, path: str) -> None:
    exe = shutil.which("espeak-ng") or shutil.which("espeak")
    subprocess.run([exe, "-s", str(RATE), "-w", path, text], check=True, timeout=30)


def synthesize(text: str) -> bytes:
    fd, path = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    try:
        with lock:
            try:
                synth_pyttsx3(text, path)
            except Exception:
                if not (shutil.which("espeak-ng") or shutil.which("espeak")):
                    raise
                synth_espeak(text, path)
        with wave.open(path, "rb") as w:              # sanity check: valid PCM WAV
            print(f"  -> {w.getframerate()} Hz, {w.getsampwidth() * 8}-bit, {w.getnchannels()} ch, "
                  f"{w.getnframes() / w.getframerate():.1f}s")
        with open(path, "rb") as f:
            return f.read()
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/":
            self._send(200, b"VoiceAI TTS server is running", "text/plain")
            return
        if url.path != "/tts":
            self._send(404, b"not found", "text/plain")
            return
        text = (parse_qs(url.query).get("text") or [""])[0].strip()[:MAX_CHARS]
        if not text:
            self._send(400, b"missing ?text=", "text/plain")
            return
        print(f"[tts] {text[:80]}")
        try:
            self._send(200, synthesize(text), "audio/wav")
        except Exception as e:  # noqa: BLE001 - report any engine error to the ESP32 log
            print("[tts] error:", e)
            self._send(500, str(e).encode(), "text/plain")

    def _send(self, code, body, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"VoiceAI TTS server on port {PORT}  (test: http://localhost:{PORT}/tts?text=hello)")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
