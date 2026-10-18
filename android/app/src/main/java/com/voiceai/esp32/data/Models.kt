package com.voiceai.esp32.data

const val DEFAULT_SYSTEM_PROMPT =
    "You are a helpful voice assistant running on a small ESP32 speaker. " +
        "Answer clearly and briefly in plain sentences. No markdown, no emojis, no lists."

/** How the phone reaches the AI. */
enum class Mode(val label: String, val hint: String) {
    DIRECT("Direct to PC", "Fastest. Phone talks to Ollama, words appear live, ESP32 speaks the answer."),
    ESP32("Through ESP32", "Like your first code. ESP32 asks Ollama and speaks the answer."),
}

/** Where answers are played. */
enum class Route(val label: String) {
    SPEAKER("Speaker"), PHONE("Phone"), BOTH("Speaker + phone"), SILENT("Silent");

    val onSpeaker get() = this == SPEAKER || this == BOTH
    val onPhone get() = this == PHONE || this == BOTH
}

/** Quick personalities: one tap changes how the AI answers. */
enum class Persona(val label: String, val emoji: String, val prompt: String) {
    ASSISTANT("Assistant", "🤖", ""),
    TEACHER("Teacher", "🎓", "Act as a friendly teacher. Explain step by step with a simple example, like to a high-school student."),
    CODER("Coder", "💻", "Act as an expert programmer. Give working code in markdown code blocks with the language name, then a short explanation."),
    TRANSLATOR("Translator", "🌐", "Act as a translator. Translate the user's text between English and Dari/Persian (whichever it is not), then give a short pronunciation tip."),
    STORY("Storyteller", "📖", "Act as a creative storyteller. Tell short, vivid, family-friendly stories."),
    COACH("Coach", "💪", "Act as a motivating study and life coach. Be encouraging and give 2-3 practical tips."),
}

data class AppSettings(
    val espHost: String = "",
    val pcHost: String = "192.168.80.189",
    val mode: Mode = Mode.DIRECT,
    val route: Route = Route.SPEAKER,
    val lang: String = "en-US",
    val autoSend: Boolean = true,
    val history: Int = 8,
    val model: String = "qwen2.5:1.5b",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val maxWords: Int = 40,
    val maxTokens: Int = 160,
    val persona: Persona = Persona.ASSISTANT,
    val temperature: Float = 0.7f,
    val textScale: Float = 1f,
    val handsFree: Boolean = false,
)

data class Usage(
    val prompt: Int,
    val completion: Int,
    val tokensPerSec: Double,
    val totalMs: Long,
    val loadMs: Long = 0,
) {
    val total get() = prompt + completion
}

data class Message(
    val role: String,               // "user" or "assistant"
    val content: String,
    val ts: Long = System.currentTimeMillis(),
    val model: String? = null,
    val usage: Usage? = null,
)

data class Chat(
    val id: String,
    val title: String,
    val messages: List<Message>,
    val updated: Long = System.currentTimeMillis(),
) {
    val tokens get() = messages.sumOf { it.usage?.total ?: 0 }
}

data class UsageRecord(
    val ts: Long,
    val chatId: String,
    val model: String,
    val prompt: Int,
    val completion: Int,
    val tokensPerSec: Double,
    val totalMs: Long,
)

/** What the ESP32 reports on /api/info */
data class DeviceInfo(
    val ip: String,
    val fw: String,
    val rssi: Int,
    val heap: Long,
    val uptimeSec: Long,
    val state: String,
    val ollamaOnline: Boolean,
    val models: List<String>,
    val ollamaHost: String,
    val model: String,
    val voiceMode: Int,
    val volume: Float,
    val numCtx: Int,
    val sessionRequests: Int,
    val sessionPrompt: Long,
    val sessionCompletion: Long,
    val minHeap: Long = 0,
    val lastReset: String = "",
)
