// ============================================================================
//  DacOutput - plays audio on the ESP32 internal 8-bit DAC (GPIO25) so it can
//  feed an analog amplifier like the PAM8406 directly.
//
//  v2.6 - the DAC NEVER STOPS:
//  The ESP-IDF "dac_continuous_write" mode has a race: when the sound buffer
//  runs empty (every pause between phrases) the DMA can stop and never take
//  data again ("Get available descriptor timeout"). That froze / cut the voice.
//
//  Now the DAC runs in "async" mode: the DMA loops over a ring of buffers
//  forever. Every time one buffer has been played, a small audio task refills
//  it - with speech if there is some, otherwise with silence. The DMA never
//  runs dry, so the bug can't happen.
//
//    voice (SAM / WAV) -> ConsumeSample -> stream buffer -> audio task -> DMA ring -> GPIO25
//
//  Safety rules kept from v2.5:
//   * ConsumeSample() always returns true (the voice library loops forever on false)
//   * drop / deadline let the firmware cut speech off instantly
//   * pushing audio never waits more than 300 ms
// ============================================================================
#pragma once
#include <AudioOutput.h>
#include <driver/dac_continuous.h>
#include <freertos/stream_buffer.h>
#include <freertos/queue.h>

class DacOutput : public AudioOutput {
  static const size_t DMA_BUF = 1024;       // bytes per DMA buffer
  static const size_t DMA_DESC = 6;         // buffers in the ring (~140 ms at 22 kHz)
  static const size_t STREAM = 4096;        // speech waiting to be played (~185 ms)
  static const size_t BUF = 512;            // samples collected before pushing

  dac_continuous_handle_t handle = nullptr;
  uint32_t openRate = 0;
  StreamBufferHandle_t stream = nullptr;
  QueueHandle_t events = nullptr;
  TaskHandle_t task = nullptr;
  volatile bool reopening = false;

  uint8_t buf[BUF];
  size_t fill = 0;
  uint16_t peak = 0;
  volatile uint8_t lvl = 0;                 // loudness for the OLED mouth
  bool cleared = false;

  // ---- DMA ring callback (interrupt): tell the audio task which buffer is free
  static bool IRAM_ATTR onDone(dac_continuous_handle_t, const dac_event_data_t *e, void *user) {
    DacOutput *self = (DacOutput *)user;
    BaseType_t woke = pdFALSE;
    if (xQueueIsQueueFullFromISR(self->events)) {
      dac_event_data_t old;
      xQueueReceiveFromISR(self->events, &old, &woke);
    }
    xQueueSendFromISR(self->events, e, &woke);
    return woke == pdTRUE;
  }

  // ---- audio task: refill each free DMA buffer with speech or silence
  static void audioTask(void *arg) {
    DacOutput *self = (DacOutput *)arg;
    static uint8_t stage[STREAM];
    static uint8_t silence[DMA_BUF];
    memset(silence, 128, sizeof silence);
    size_t stageLen = 0;
    dac_event_data_t e;
    for (;;) {
      if (xQueueReceive(self->events, &e, portMAX_DELAY) != pdTRUE) continue;
      if (self->reopening || !self->handle) { stageLen = 0; continue; }
      if (stageLen < sizeof stage)
        stageLen += xStreamBufferReceive(self->stream, stage + stageLen, sizeof stage - stageLen, 0);
      size_t loaded = 0;
      if (stageLen) {
        dac_continuous_write_asynchronously(self->handle, (uint8_t *)e.buf, e.buf_size, stage, stageLen, &loaded);
        if (loaded >= stageLen) stageLen = 0;
        else if (loaded) { memmove(stage, stage + loaded, stageLen - loaded); stageLen -= loaded; }
      } else {
        dac_continuous_write_asynchronously(self->handle, (uint8_t *)e.buf, e.buf_size, silence, sizeof silence, &loaded);
      }
    }
  }

  void close() {
    if (!handle) return;
    reopening = true;
    dac_continuous_stop_async_writing(handle);
    dac_continuous_disable(handle);
    dac_continuous_del_channels(handle);
    handle = nullptr;
    xQueueReset(events);
    reopening = false;
  }

  void open() {
    if (!stream) {
      stream = xStreamBufferCreate(STREAM, 1);
      events = xQueueCreate(DMA_DESC + 2, sizeof(dac_event_data_t));
      xTaskCreatePinnedToCore(audioTask, "audio", 4096, this, 6, &task, 1);
    }
    close();
    xStreamBufferReset(stream);
    dac_continuous_config_t c = {};
    c.chan_mask = DAC_CHANNEL_MASK_CH0;          // GPIO25 only
    c.desc_num = DMA_DESC;
    c.buf_size = DMA_BUF;
    c.freq_hz = hertz ? hertz : 22050;
    c.offset = 0;
    c.clk_src = DAC_DIGI_CLK_SRC_APLL;           // accurate low sample rates
    c.chan_mode = DAC_CHANNEL_MODE_SIMUL;
    dac_event_callbacks_t cbs = {};
    cbs.on_convert_done = onDone;
    if (dac_continuous_new_channels(&c, &handle) == ESP_OK &&
        dac_continuous_register_event_callback(handle, &cbs, this) == ESP_OK &&
        dac_continuous_enable(handle) == ESP_OK &&
        dac_continuous_start_async_writing(handle) == ESP_OK) {
      openRate = c.freq_hz;
      Serial.printf("[dac] running at %u Hz (never-stop mode)\n", (unsigned)openRate);
    } else {
      Serial.println("[dac] could not start the DAC");
      if (handle) { dac_continuous_disable(handle); dac_continuous_del_channels(handle); handle = nullptr; }
      openRate = 0;
    }
  }

  bool dropping() const {
    return drop || (deadline && (int32_t)(millis() - deadline) > 0);
  }

public:
  volatile bool drop = false;     // true = throw samples away (stop speaking now)
  volatile uint32_t deadline = 0; // millis() after which samples are thrown away (0 = none)
  volatile uint32_t stalls = 0;   // how many times audio could not be pushed in time

  DacOutput() { hertz = 22050; channels = 1; SetGain(1.0f); }
  ~DacOutput() override { close(); }

  bool SetRate(int hz) override { flush(); hertz = hz; return true; }
  bool SetChannels(int ch) override { channels = ch; return true; }

  bool begin() override {
    if (!handle || openRate != hertz) open();
    cleared = false;
    return handle != nullptr;
  }

  bool ConsumeSample(int16_t s[2]) override {
    if (dropping()) {                                     // stop now: forget queued speech
      if (!cleared && stream) { xStreamBufferReset(stream); cleared = true; }
      lvl = 0; fill = 0;
      return true;                                        // must stay true, see top
    }
    cleared = false;
    if (!handle || openRate != hertz) open();
    if (!handle) return true;
    int32_t v = channels == 2 ? ((int32_t)s[0] + s[1]) / 2 : s[0];
    v = Amplify((int16_t)v);
    uint16_t a = v < 0 ? -v : v;
    if (a > peak) peak = a;
    buf[fill++] = (uint8_t)((v + 32768) >> 8);           // 16-bit signed -> 8-bit unsigned
    if (fill == BUF) flush();
    return true;
  }

  void flush() override {
    if (fill && stream && handle && !dropping()) {
      size_t sent = xStreamBufferSend(stream, buf, fill, pdMS_TO_TICKS(300));
      if (sent < fill) {
        stalls++;
        Serial.println("[dac] audio not playing out - dropping this sentence");
        drop = true;
      }
    }
    fill = 0;
    lvl = (uint8_t)min<uint32_t>(255, peak >> 6);         // ~ 0..255
    peak = 0;
  }

  /** Loudness of the sound playing right now, 0..255. */
  uint8_t level() const { return lvl; }
  void quiet() { lvl = 0; }

  // Speech finished: push what is left. The audio task plays silence after it,
  // so the speaker gets no pop and the DAC keeps running.
  bool stop() override {
    flush();
    lvl = 0;
    return true;
  }
};
