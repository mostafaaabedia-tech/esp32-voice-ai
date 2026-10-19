// ============================================================================
//  DacOutput - plays audio on the ESP32 internal 8-bit DAC (GPIO25) so it can
//  feed an analog amplifier like the PAM8406 directly.
//
//  Why not the library class?  In ESP8266Audio 2.x the old
//  AudioOutputI2S(0, INTERNAL_DAC) constructor silently ignores the DAC mode
//  (no sound), and AudioOutputInternalDAC ignores SetGain() and can play SAM
//  at the wrong speed. This small class fixes all three.
//
//  NEVER-HANG RULES (v2.5):
//   * writes to the DAC time out after 250 ms instead of waiting forever. If the
//     DAC stops taking data, the rest of the sentence is dropped and the DAC is
//     restarted for the next one. (Before, one stall froze the whole ESP32 on
//     "speaking" - the frozen subtitle bug.)
//   * ConsumeSample() always returns true. The voice library loops forever if
//     it returns false.
//   * drop / deadline: the firmware can cut speech off instantly (Stop button,
//     new question) and every phrase has a time limit.
// ============================================================================
#pragma once
#include <AudioOutput.h>
#include <driver/dac_continuous.h>

class DacOutput : public AudioOutput {
  dac_continuous_handle_t handle = nullptr;
  uint32_t openRate = 0;
  static const size_t BUF = 512;
  uint8_t buf[BUF];
  size_t fill = 0;
  uint16_t peak = 0;
  volatile uint8_t lvl = 0;      // loudness for the OLED mouth
  bool needReopen = false;

  void open() {
    close();
    dac_continuous_config_t c = {};
    c.chan_mask = DAC_CHANNEL_MASK_CH0;          // GPIO25 only
    c.desc_num = 4;
    c.buf_size = 1024;
    c.freq_hz = hertz ? hertz : 22050;
    c.offset = 0;
    c.clk_src = DAC_DIGI_CLK_SRC_APLL;           // accurate low sample rates
    c.chan_mode = DAC_CHANNEL_MODE_SIMUL;
    if (dac_continuous_new_channels(&c, &handle) == ESP_OK && dac_continuous_enable(handle) == ESP_OK) {
      openRate = c.freq_hz;
    } else {
      Serial.println("[dac] could not start the DAC");
      if (handle) { dac_continuous_del_channels(handle); handle = nullptr; }
      openRate = 0;
    }
    needReopen = false;
  }

  void close() {
    if (handle) {
      dac_continuous_disable(handle);
      dac_continuous_del_channels(handle);
      handle = nullptr;
    }
  }

  /** Send bytes to the DAC; never blocks for more than 250 ms. */
  void writeOut(uint8_t *data, size_t n) {
    if (!handle || n == 0) return;
    esp_err_t e = dac_continuous_write(handle, data, n, NULL, 250);
    if (e != ESP_OK) {
      stalls++;
      Serial.printf("[dac] stalled (err %d) - dropping this sentence, DAC restarts next time\n", e);
      drop = true;            // let the voice finish instantly (no more writes)
      needReopen = true;      // fresh DAC for the next sentence
    }
  }

  bool dropping() const {
    return drop || (deadline && (int32_t)(millis() - deadline) > 0);
  }

public:
  volatile bool drop = false;     // true = throw samples away (stop speaking now)
  volatile uint32_t deadline = 0; // millis() after which samples are thrown away (0 = none)
  volatile uint32_t stalls = 0;   // how many times the DAC stopped responding

  DacOutput() { hertz = 22050; channels = 1; SetGain(1.0f); }
  ~DacOutput() override { close(); }

  bool SetRate(int hz) override { flush(); hertz = hz; return true; }
  bool SetChannels(int ch) override { channels = ch; return true; }

  bool begin() override {
    if (!handle || openRate != hertz || needReopen) open();
    return handle != nullptr;
  }

  bool ConsumeSample(int16_t s[2]) override {
    if (dropping()) { lvl = 0; fill = 0; return true; }   // must stay true, see top
    if (!handle || openRate != hertz || needReopen) open();
    if (!handle) return true;
    int32_t v = channels == 2 ? ((int32_t)s[0] + s[1]) / 2 : s[0];
    v = Amplify((int16_t)v);
    uint16_t a = v < 0 ? -v : v;
    if (a > peak) peak = a;
    buf[fill++] = (uint8_t)((v + 32768) >> 8);   // 16-bit signed -> 8-bit unsigned
    if (fill == BUF) flush();
    return true;
  }

  void flush() override {
    if (fill && !dropping()) writeOut(buf, fill);
    fill = 0;
    lvl = (uint8_t)min<uint32_t>(255, peak >> 6);   // ~ 0..255
    peak = 0;
  }

  /** Loudness of the sound playing right now, 0..255. */
  uint8_t level() const { return lvl; }
  void quiet() { lvl = 0; }

  // Finish the sound and leave the DAC at mid level (no pop, no DC on the amp)
  bool stop() override {
    flush();
    lvl = 0;
    if (handle && !needReopen) {
      memset(buf, 128, BUF);
      writeOut(buf, BUF);
    }
    fill = 0;
    return true;
  }
};
