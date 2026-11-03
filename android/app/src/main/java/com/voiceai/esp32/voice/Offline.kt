package com.voiceai.esp32.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Built-in offline speech (sherpa-onnx), no internet and no Google app needed:
 *  - listening: streaming Zipformer (English) in assets/asr
 *  - speaking: KittenTTS voice pack with 8 voices in assets/tts
 * The models are loaded once in the background when the app starts.
 */
object OfflineEngine {
    @Volatile var recognizer: OnlineRecognizer? = null; private set
    @Volatile var tts: OfflineTts? = null; private set
    @Volatile var asrStatus = "not loaded"; private set
    @Volatile var ttsStatus = "not loaded"; private set
    @Volatile private var started = false

    val asrReady get() = recognizer != null
    val ttsReady get() = tts != null
    const val VOICES = 8

    fun load(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        asrStatus = "loading"
        ttsStatus = "loading"
        // load both at the same time: the mic is ready a few seconds sooner
        Thread({ asrStatus = try { loadAsr(app); "ready" } catch (e: Throwable) { "not available (${e.message})" } }, "offline-asr-load").start()
        Thread({ ttsStatus = try { loadTts(app); "ready" } catch (e: Throwable) { "not available (${e.message})" } }, "offline-tts-load").start()
    }

    private fun hasAsset(ctx: Context, path: String) = runCatching { ctx.assets.open(path).close(); true }.getOrDefault(false)

    private fun loadAsr(ctx: Context) {
        if (!hasAsset(ctx, "asr/encoder.int8.onnx")) throw IllegalStateException("model missing in this build")
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "asr/encoder.int8.onnx",
                    decoder = "asr/decoder.onnx",
                    joiner = "asr/joiner.int8.onnx",
                ),
                tokens = "asr/tokens.txt",
                numThreads = 2,
                modelType = "zipformer",
            ),
            // the listener decides itself when you finished (Settings -> "Wait for me"),
            // these rules are only a safety net
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 12.0f, 0.0f),
                rule2 = EndpointRule(true, 8.0f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 40.0f),
            ),
            enableEndpoint = true,
            // looks at several guesses instead of only the first one: fewer missed words
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
        )
        recognizer = OnlineRecognizer(ctx.assets, cfg)
    }

    private fun loadTts(ctx: Context) {
        if (!hasAsset(ctx, "tts/model.int8.onnx")) throw IllegalStateException("voice pack missing in this build")
        val data = copyAssetDir(ctx, "tts/espeak-ng-data")
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(
                    model = "tts/model.int8.onnx",
                    voices = "tts/voices.bin",
                    tokens = "tts/tokens.txt",
                    dataDir = data,
                ),
                numThreads = 2,
                provider = "cpu",
            ),
        )
        tts = OfflineTts(ctx.assets, cfg)
    }

    /** espeak-ng needs real files, so copy its data out of the APK once. */
    private fun copyAssetDir(ctx: Context, path: String): String {
        val out = File(ctx.filesDir, path)
        val mark = File(out, "copied-v1")
        if (mark.exists()) return out.absolutePath
        out.deleteRecursively()
        fun copy(p: String, dst: File) {
            val list = ctx.assets.list(p).orEmpty()
            if (list.isEmpty()) {
                dst.parentFile?.mkdirs()
                ctx.assets.open(p).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            } else {
                dst.mkdirs()
                list.forEach { copy("$p/$it", File(dst, it)) }
            }
        }
        copy(path, out)
        mark.writeText("ok")
        return out.absolutePath
    }
}

/** Offline speech recognition from the microphone, with live words. */
class OfflineListener {
    @Volatile private var running = false
    @Volatile private var stopReq = false
    private val main = Handler(Looper.getMainLooper())

    val active get() = running

    /** How long you can pause (thinking of a word) before your turn ends. */
    @Volatile var patienceMs = 2200

    fun start(
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
        onFinal: (String) -> Unit,
        onFail: (String) -> Unit,
        onSpeechStart: () -> Unit,
        onSpeechEnd: () -> Unit,
    ) {
        cancel()
        val rec = OfflineEngine.recognizer
        if (rec == null) {
            onFail(if (OfflineEngine.asrStatus == "loading" || OfflineEngine.asrStatus == "not loaded") "Offline voice is still loading, try again in a moment" else "Offline speech is ${OfflineEngine.asrStatus}")
            return
        }
        running = true
        stopReq = false
        Thread({ loop(rec, onPartial, onLevel, onFinal, onFail, onSpeechStart, onSpeechEnd) }, "offline-asr").start()
    }

    @SuppressLint("MissingPermission")   // the app asks for the microphone before starting
    private fun loop(
        rec: OnlineRecognizer,
        onPartial: (String) -> Unit, onLevel: (Float) -> Unit, onFinal: (String) -> Unit,
        onFail: (String) -> Unit, onSpeechStart: () -> Unit, onSpeechEnd: () -> Unit,
    ) {
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr))
        } catch (e: Exception) { null }
        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            ar?.release()
            running = false
            main.post { onFail("The microphone is busy") }
            return
        }
        // the phone's own noise cleaner + volume leveller, when it has them
        val ns = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(ar.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        val agc = runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(ar.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        val stream = rec.createStream()
        val buf = ShortArray(1600)                 // 0.1 s
        var last = ""
        var spoke = false
        var result: String? = null
        var noise = 0.01f                          // background noise level (learned)
        var gain = 2f                              // makes quiet / far voices louder
        val startMs = System.currentTimeMillis()
        var lastVoiceMs = startMs
        try {
            ar.startRecording()
            while (running) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                val now = System.currentTimeMillis()
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i] / 32768f; sum += (v * v).toDouble() }
                val rms = sqrt(sum / n).toFloat()
                // voice or background? the noise level follows the quiet parts
                val voice = rms > maxOf(0.008f, noise * 2.8f)
                noise = if (voice) noise * 0.999f + rms * 0.001f else noise * 0.95f + rms * 0.05f
                if (voice) {
                    lastVoiceMs = now
                    gain = (gain * 0.9f + (0.1f / maxOf(rms, 0.004f)).coerceIn(1f, 8f) * 0.1f)
                }
                val f = FloatArray(n)
                for (i in 0 until n) {
                    val v = buf[i] / 32768f * gain
                    f[i] = if (v > 0.95f) 0.95f else if (v < -0.95f) -0.95f else v      // no harsh clipping
                }
                main.post { onLevel((rms * gain * 10f).coerceIn(0f, 1f)) }
                stream.acceptWaveform(f, sr)
                while (rec.isReady(stream)) rec.decode(stream)
                val text = pretty(rec.getResult(stream).text)
                if (text.isNotEmpty() && text != last) {
                    last = text
                    lastVoiceMs = now
                    if (!spoke) { spoke = true; main.post { onSpeechStart() } }
                    main.post { onPartial(text) }
                }
                if (stopReq) { result = last; break }
                // your turn ends only after a real pause (you can stop to think of a word)
                val quietFor = now - lastVoiceMs
                if (last.isNotEmpty() && quietFor > patienceMs) { result = last; main.post { onSpeechEnd() }; break }
                if (last.isEmpty() && now - startMs > 10_000) { main.post { onFail("Didn't hear anything") }; break }
                if (now - startMs > 45_000) { result = last; break }
                if (rec.isEndpoint(stream)) {
                    if (last.isNotEmpty()) { result = last; main.post { onSpeechEnd() }; break }
                    rec.reset(stream)
                }
            }
        } catch (e: Exception) {
            main.post { onFail("Microphone error: ${e.message}") }
        } finally {
            runCatching { ar.stop() }
            ar.release()
            runCatching { ns?.release(); agc?.release() }
            stream.release()
        }
        val deliver = running && result != null
        running = false
        if (deliver) { val r = result ?: ""; main.post { onFinal(r) } }
    }

    /** Stop and deliver what was heard so far. */
    fun stop() { stopReq = true }

    /** Stop without a result. */
    fun cancel() { running = false }

    companion object {
        /** The model writes in CAPITALS: make it a normal sentence. */
        fun pretty(raw: String): String {
            val t = raw.trim().lowercase()
                .replace(Regex("\\b(zeffer|zefer|zephir|zephyre|zafir|zefir)\\b"), "zephyr")   // the wizard's name
                .replace(Regex("\\b(uh|um|erm|hmm|ah)\\b"), "").replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) return t
            return t.replace(Regex("\\bi\\b"), "I")
                .replace(Regex("\\bi'(m|ll|ve|d)\\b")) { "I'" + it.groupValues[1] }
                .replaceFirstChar { it.uppercase() }
        }
    }
}

/** Plays the built-in voice pack. Pitch is changed by playing faster/slower while the voice talks slower/faster. */
class OfflineSpeaker {
    @Volatile var active = false; private set
    @Volatile private var gen = 0
    private val lock = Any()
    private var track: AudioTrack? = null

    var onStart: (() -> Unit)? = null
    var onWord: (() -> Unit)? = null
    var onProgress: ((Int, Int) -> Unit)? = null
    var onDone: (() -> Unit)? = null

    private class Chunk(val start: Int, val len: Int, val samples: FloatArray, val rate: Int, val pauseMs: Int)
    private object End

    fun speak(text: String, voice: Int, pitch: Float, rate: Float): Boolean {
        val tts = OfflineEngine.tts ?: return false
        stop()
        val my = ++gen
        active = true
        Thread({ play(tts, text, voice.coerceIn(0, OfflineEngine.VOICES - 1), pitch.coerceIn(0.5f, 1.9f), rate.coerceIn(0.5f, 2f), my) }, "offline-tts").start()
        return true
    }

    private fun sentences(text: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val m = Regex("[^.!?;\\n]+[.!?;]*").findAll(text)
        for (r in m) {
            var s = r.value
            var start = r.range.first
            // very long sentences: cut at commas so the first words come out quickly
            while (s.length > 180) {
                val cut = s.lastIndexOf(',', 160).takeIf { it > 40 } ?: s.lastIndexOf(' ', 160).takeIf { it > 40 } ?: 160
                out.add(start to s.substring(0, cut + 1))
                start += cut + 1
                s = s.substring(cut + 1)
            }
            if (s.isNotBlank()) out.add(start to s)
        }
        return out
    }

    private fun play(tts: OfflineTts, text: String, voice: Int, pitch: Float, rate: Float, my: Int) {
        val q = LinkedBlockingQueue<Any>()
        val parts = sentences(text)
        // make the next sentence while the current one is playing
        Thread({
            for ((start, s) in parts) {
                if (gen != my) break
                val a = try { tts.generate(s.trim(), sid = voice, speed = rate / pitch) } catch (e: Throwable) { null }
                // a short breath after each sentence (longer when speaking slowly)
                val end = s.trimEnd().lastOrNull()
                val pause = ((if (end == '.' || end == '!' || end == '?') 380 else 200) / rate).toInt()
                if (a != null && a.samples.isNotEmpty()) q.put(Chunk(start, s.length, a.samples, a.sampleRate, pause))
            }
            q.put(End)
        }, "offline-tts-gen").start()

        var started = false
        var written = 0L
        var lastPos = 0
        var t: AudioTrack? = null
        while (gen == my) {
            val item = q.poll(250, TimeUnit.MILLISECONDS) ?: continue
            if (item === End) break
            val c = item as Chunk
            val tr: AudioTrack = t ?: run {
                val sr = (c.rate * pitch).toInt().coerceIn(8000, 48000)
                val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                val made = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(minBuf, sr / 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                val ok = synchronized(lock) {
                    if (gen == my) track = made
                    gen == my
                }
                if (!ok) { made.release(); return }
                made.play()
                t = made
                made
            }
            if (!started) { started = true; onStart?.invoke() }
            val piece = c.rate / 20                       // 50 ms
            var off = 0
            while (off < c.samples.size && gen == my) {
                val n = min(piece, c.samples.size - off)
                val w = tr.write(c.samples, off, n, AudioTrack.WRITE_BLOCKING)
                if (w <= 0) break
                off += w
                written += w
                val pos = (c.start + c.len.toLong() * off / c.samples.size).toInt().coerceAtMost(text.length)
                if (pos > lastPos) {
                    if (text.substring(lastPos, pos).contains(' ')) onWord?.invoke()
                    lastPos = pos
                    onProgress?.invoke(pos, text.length)
                }
            }
            if (gen == my && c.pauseMs > 0) {
                val silence = FloatArray(c.rate * c.pauseMs / 1000)
                val w = tr.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                if (w > 0) written += w
            }
        }
        val last = t
        if (gen == my && last != null) {
            // let the last words come out of the speaker
            val end = System.currentTimeMillis() + 3000
            while (gen == my && last.playbackHeadPosition < written && System.currentTimeMillis() < end) Thread.sleep(30)
        }
        synchronized(lock) {
            if (track === last) track = null
        }
        runCatching { last?.stop(); last?.release() }
        if (gen == my) {
            active = false
            onDone?.invoke()
        }
    }

    fun stop() {
        gen++
        active = false
        synchronized(lock) {
            track?.let { runCatching { it.pause(); it.flush() } }
        }
    }
}
