package com.voiceai.esp32.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Android speech recognition with live partial results. Use from the main thread. */
class VoiceInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

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
        recognizer?.stopListening()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Stop and free the recognizer. Destroying it inside one of its own callbacks
     * crashes on some phones, so the destroy is done a moment later.
     */
    fun cancel() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        main.post { runCatching { r.destroy() } }
    }
}

/** Phone text-to-speech. */
class PhoneVoice(context: Context) {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }

    fun speak(text: String, lang: String): Boolean {
        if (!ready) return false
        return runCatching {
            tts.language = Locale.forLanguageTag(lang)
            tts.speak(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), TextToSpeech.QUEUE_FLUSH, null, "voiceai")
            true
        }.getOrDefault(false)
    }

    fun stop() {
        if (ready) tts.stop()
    }

    val speaking: Boolean get() = ready && runCatching { tts.isSpeaking }.getOrDefault(false)

    fun shutdown() = tts.shutdown()
}
