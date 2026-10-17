// ============================================================================
//  DacOutput - plays audio on the ESP32 internal 8-bit DAC (GPIO25) so it can
//  feed an analog amplifier like the PAM8406 directly.
//
//  Why not the library class?  In ESP8266Audio 2.x the old
//  AudioOutputI2S(0, INTERNAL_DAC) constructor silently ignores the DAC mode
//  (no sound), and AudioOutputInternalDAC ignores SetGain() and can play SAM
//  at the wrong speed. This small class fixes all three.
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
      handle = nullptr;
      openRate = 0;
    }
  }

  void close() {
    if (handle) {
      dac_continuous_disable(handle);
      dac_continuous_del_channels(handle);
      handle = nullptr;
    }
  }

public:
  DacOutput() { hertz = 22050; channels = 1; SetGain(1.0f); }
  ~DacOutput() override { close(); }

  bool SetRate(int hz) override { flush(); hertz = hz; return true; }
  bool SetChannels(int ch) override { channels = ch; return true; }

  bool begin() override {
    if (!handle || openRate != hertz) open();
    return handle != nullptr;
  }

  bool ConsumeSample(int16_t s[2]) override {
    if (!handle || openRate != hertz) open();
    int32_t v = channels == 2 ? ((int32_t)s[0] + s[1]) / 2 : s[0];
    v = Amplify((int16_t)v);
    buf[fill++] = (uint8_t)((v + 32768) >> 8);   // 16-bit signed -> 8-bit unsigned
    if (fill == BUF) flush();
    return true;
  }

  void flush() override {
    if (handle && fill) dac_continuous_write(handle, buf, fill, NULL, -1);
    fill = 0;
  }

  // Finish the sound and leave the DAC at mid level (no pop, no DC on the amp)
  bool stop() override {
    flush();
    if (handle) {
      memset(buf, 128, BUF);
      dac_continuous_write(handle, buf, BUF, NULL, -1);
    }
    return true;
  }
};
