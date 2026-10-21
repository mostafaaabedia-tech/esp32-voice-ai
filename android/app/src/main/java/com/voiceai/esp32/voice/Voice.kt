package com.voiceai.esp32.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speech input with live partial results. Use from the main thread.
 * Offline (built-in model, English) by default; the Android / Google recognizer
 * is used for other languages or when you switch it on in Settings.
 */
class VoiceInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private val offlineListener = OfflineListener()

    /** Use the built-in offline recognizer (English). */
    var offline = true

    private fun useOffline(lang: String) = offline && lang.startsWith("en") && OfflineEngine.asrStatus != "not loaded" &&
        !OfflineEngine.asrStatus.startsWith("not available")

    val available: Boolean get() = (offline && OfflineEngine.asrReady) || SpeechRecognizer.isRecognitionAvailable(context)

    fun start(
        lang: String,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
        onFinal: (String) -> Unit,
        onFail: (String) -> Unit,
        onSpeechStart: () -> Unit = {},
        onSpeechEnd: () -> Unit = {},
    ) {
        cancel()
        if (useOffline(lang)) {
            offlineListener.start(onPartial, onLevel, onFinal, onFail, onSpeechStart, onSpeechEnd)
            return
        }
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            onFail("Speech service could not start")
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() = onSpeechStart()
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onSpeechEnd()
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { if (it.isNotBlank()) onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                onFinal(text)
            }

            override fun onError(error: Int) {
                onFail(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Try again."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Speech needs internet, or download the offline speech pack in the Google app."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Try again."
                        else -> "Voice error ($error)"
                    }
                )
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // stop sooner after you finish talking
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            onFail("Speech service could not start")
        }
    }

    /** Stop listening and deliver what was heard (onFinal). */
    fun stop() {
        if (offlineListener.active) offlineListener.stop()
        recognizer?.stopListening()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Stop and free the recognizer. Destroying it inside one of its own callbacks
     * crashes on some phones, so the destroy is done a moment later.
     */
    fun cancel() {
        offlineListener.cancel()
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        main.post { runCatching { r.destroy() } }
    }
}

/** Phone text-to-speech (the companion's voice). */
class PhoneVoice(context: Context) {
    @Volatile private var ready = false
    @Volatile private var length = 0
    @Volatile var active = false; private set

    /** Called while speaking: (position in the text, text length). Used to sync the OLED subtitles. */
    var onProgress: ((Int, Int) -> Unit)? = null
    /** Every new word (lip sync). */
    var onWord: (() -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null

    var pitch = 1f
    var rate = 1f
    var voiceName = ""
    /** Use the built-in voice pack (works without internet). */
    var offline = true
    /** Which of the 8 built-in voices. */
    var offlineVoice = 0

    private val pack = OfflineSpeaker().also { p ->
        p.onStart = { active = true; onStart?.invoke() }
        p.onWord = { onWord?.invoke() }
        p.onProgress = { pos, len -> onProgress?.invoke(pos, len) }
        p.onDone = { active = false; onDone?.invoke() }
    }

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) runCatching { setListener() }
    }

    private fun setListener() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { active = true; onStart?.invoke() }
            override fun onDone(utteranceId: String?) { active = false; onDone?.invoke() }
            @Deprecated("old API") override fun onError(utteranceId: String?) { active = false; onDone?.invoke() }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { active = false }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                onWord?.invoke()
                onProgress?.invoke(start, length)
            }
        })
    }

    /** Voices installed for this language (names to pick in Settings). */
    fun voices(lang: String): List<String> = runCatching {
        val l = Locale.forLanguageTag(lang).language
        tts.voices.orEmpty().filter { it.locale.language == l && !it.isNetworkConnectionRequired }
            .map { it.name }.sorted()
    }.getOrDefault(emptyList())

    fun speak(text: String, lang: String): Boolean {
        if (offline && lang.startsWith("en") && OfflineEngine.ttsReady) {
            if (ready) runCatching { tts.stop() }
            val t = text.take(4000)
            length = t.length
            active = true
            return pack.speak(t, offlineVoice, pitch, rate)
        }
        if (!ready) return false
        return runCatching {
            val v = voiceName.takeIf { it.isNotBlank() }?.let { n -> tts.voices.orEmpty().firstOrNull { it.name == n } }
            if (v != null) tts.voice = v else tts.language = Locale.forLanguageTag(lang)
            tts.setPitch(pitch)
            tts.setSpeechRate(rate)
            val t = text.take(TextToSpeech.getMaxSpeechInputLength() - 1)
            length = t.length
            active = true
            tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "voiceai")
            true
        }.getOrDefault(false)
    }

    fun stop() {
        active = false
        pack.stop()
        if (ready) tts.stop()
    }

    val speaking: Boolean get() = active || pack.active || (ready && runCatching { tts.isSpeaking }.getOrDefault(false))

    fun shutdown() = tts.shutdown()
}
