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
)
