package com.voiceai.esp32

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceai.esp32.data.AppSettings
import com.voiceai.esp32.data.Chat
import com.voiceai.esp32.data.DeviceInfo
import com.voiceai.esp32.data.Message
import com.voiceai.esp32.data.Mode
import com.voiceai.esp32.data.Persona
import com.voiceai.esp32.data.Store
import com.voiceai.esp32.data.Usage
import com.voiceai.esp32.data.UsageRecord
import com.voiceai.esp32.net.Api
import com.voiceai.esp32.voice.PhoneVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Screen { CHAT, USAGE, SETTINGS }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    val phoneVoice = PhoneVoice(app)

    // ------------------------------------------------------------------ state
    var settings by mutableStateOf(store.loadSettings()); private set
    val chats = mutableStateListOf<Chat>().apply { addAll(store.loadChats().sortedByDescending { it.updated }) }
    var currentId by mutableStateOf(store.currentChatId); private set
    val usage = mutableStateListOf<UsageRecord>().apply { addAll(store.loadUsage()) }

    var screen by mutableStateOf(Screen.CHAT)
    var busy by mutableStateOf(false); private set
    var liveText by mutableStateOf(""); private set          // answer while it streams
    var startedAt by mutableStateOf(0L); private set
    var error by mutableStateOf<String?>(null); private set

    var device by mutableStateOf<DeviceInfo?>(null); private set
    var deviceOnline by mutableStateOf(false); private set
    var pcOnline by mutableStateOf(false); private set
    var pcModels by mutableStateOf(listOf<String>()); private set
    var toast by mutableStateOf<String?>(null)
    /** Text to put in the input box (edit question, spoken text when auto-send is off). */
    var draft by mutableStateOf<String?>(null)
    /** Increases when hands-free mode wants the mic to start again. */
    var listenRequest by mutableIntStateOf(0); private set

    private var askJob: Job? = null

    val current: Chat? get() = chats.firstOrNull { it.id == currentId }
    val messages: List<Message> get() = current?.messages.orEmpty()

    /** Green dot: the path the questions take is working. */
    val aiReachable: Boolean
        get() = when (settings.mode) {
            Mode.DIRECT -> pcOnline
            Mode.ESP32 -> deviceOnline && device?.ollamaOnline == true
        }

    val modelChoices: List<String>
        get() = (pcModels + device?.models.orEmpty() + settings.model).distinct().filter { it.isNotBlank() }

    init {
        if (current == null) currentId = null
        viewModelScope.launch {
            while (isActive) {
                if (!busy) refreshStatus()
                delay(if (device?.state == "speaking") 1500 else 6000)
            }
        }
    }

    // --------------------------------------------------------------- settings
    fun updateSettings(s: AppSettings) {
        settings = s
        store.saveSettings(s)
    }

    /** Saves AI settings on the ESP32 too, so both modes behave the same. */
    fun pushToDevice(extra: JSONObject? = null, quiet: Boolean = false) {
        val host = settings.espHost
        if (host.isBlank()) {
            if (!quiet) toast = "Saved on the phone. Add the ESP32 address to save there too."
            return
        }
        val s = settings
        val patch = extra ?: JSONObject()
            .put("ollama_host", s.pcHost.substringBefore(':'))
            .put("model", s.model)
            .put("system_prompt", s.systemPrompt)
            .put("max_words", s.maxWords)
            .put("num_predict", s.maxTokens)
        viewModelScope.launch {
            try {
                Api.espConfig(host, patch)
                if (!quiet) toast = "Saved to ESP32"
                refreshStatus()
            } catch (e: Exception) {
                toast = e.message
            }
        }
    }

    suspend fun refreshStatus() {
        val s = settings
        if (s.espHost.isNotBlank()) {
            try {
                device = Api.espInfo(s.espHost)
                deviceOnline = true
            } catch (e: Exception) {
                deviceOnline = false
            }
        } else deviceOnline = false

        if (s.pcHost.isNotBlank()) {
            try {
                pcModels = Api.ollamaModels(s.pcHost)
                pcOnline = true
            } catch (e: Exception) {
                pcOnline = false
            }
        } else pcOnline = false
    }

    fun refreshNow() = viewModelScope.launch { refreshStatus() }

    // ------------------------------------------------------------------ chats
    fun newChat() {
        if (busy) { toast = "Wait for the answer to finish"; return }
        currentId = null
        store.currentChatId = null
        error = null
        screen = Screen.CHAT
    }

    fun openChat(id: String) {
        if (busy) { toast = "Wait for the answer to finish"; return }
        currentId = id
        store.currentChatId = id
        error = null
        screen = Screen.CHAT
    }

    fun deleteChat(id: String) {
        if (busy && id == currentId) { toast = "Wait for the answer to finish"; return }
        chats.removeAll { it.id == id }
        if (currentId == id) { currentId = null; store.currentChatId = null }
        saveChats()
    }

    fun renameChat(id: String, title: String) {
        val i = chats.indexOfFirst { it.id == id }
        if (i < 0 || title.isBlank()) return
        chats[i] = chats[i].copy(title = title.trim().take(60))
        saveChats()
    }

    /** Edit a question: put it back in the box and remove it (and everything after it). */
    fun editFrom(index: Int) {
        val c = current ?: return
        if (busy) { toast = "Wait for the answer to finish"; return }
        val m = c.messages.getOrNull(index) ?: return
        putChat(c.copy(messages = c.messages.take(index)))
        draft = m.content
    }

    fun exportText(c: Chat): String = buildString {
        append("VoiceAI chat: ${c.title}\n\n")
        c.messages.forEach { m ->
            append(if (m.role == "user") "You: " else "AI: ")
            append(m.content.trim()).append("\n\n")
        }
        append("Made with VoiceAI by Mostafa Mesbah Aabedi & Sebghatullah Haidari")
    }

    fun systemPrompt(s: AppSettings): String {
        val words = if (s.persona == Persona.CODER) s.maxWords * 3 else s.maxWords
        val base = when (s.persona) {
            Persona.ASSISTANT -> s.systemPrompt
            Persona.CODER -> s.persona.prompt
            else -> s.systemPrompt + " " + s.persona.prompt
        }
        return "$base Keep every answer under $words words."
    }

    private fun putChat(c: Chat) {
        val i = chats.indexOfFirst { it.id == c.id }
        if (i >= 0) chats.removeAt(i)
        chats.add(0, c)
        currentId = c.id
        store.currentChatId = c.id
        saveChats()
    }

    private fun saveChats() {
        val copy = chats.toList()
        viewModelScope.launch(Dispatchers.IO) { store.saveChats(copy) }
    }

    private fun title(text: String): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return if (t.length > 40) t.take(38).substringBeforeLast(' ') + "…" else t
    }

    // -------------------------------------------------------------------- ask
    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        val base = current ?: Chat(UUID.randomUUID().toString(), title(q), emptyList())
        val chat = base.copy(
            title = if (base.messages.isEmpty()) title(q) else base.title,
            messages = base.messages + Message("user", q),
            updated = System.currentTimeMillis(),
        )
        putChat(chat)
        ask(chat)
    }

    fun regenerate() {
        val c = current ?: return
        if (busy) return
        val msgs = if (c.messages.lastOrNull()?.role == "assistant") c.messages.dropLast(1) else c.messages
        val chat = c.copy(messages = msgs)
        putChat(chat)
        ask(chat)
    }

    fun retry() {
        current?.let { if (!busy) ask(it) }
    }

    fun stop() {
        askJob?.cancel()
        Api.cancelStream()
        stopAudio()
    }

    private fun ask(chat: Chat) {
        error = null
        liveText = ""
        busy = true
        startedAt = System.currentTimeMillis()
        val s = settings

        val history = chat.messages.takeLast(s.history.coerceIn(1, 30)).dropWhile { it.role != "user" }
        askJob = viewModelScope.launch {
            try {
                val model: String
                val result: Pair<String, Usage>
                when (s.mode) {
                    Mode.DIRECT -> {
                        if (s.pcHost.isBlank()) throw IllegalStateException("Add your PC address in Settings")
                        face("thinking")
                        model = s.model
                        val msgs = JSONArray()
                        msgs.put(JSONObject().put("role", "system").put("content", systemPrompt(s)))
                        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content.take(2000))) }
                        result = Api.ollamaChat(s.pcHost, model, msgs, s.maxTokens, s.temperature) { piece ->
                            withContext(Dispatchers.Main) { liveText += piece }
                        }
                    }
                    Mode.ESP32 -> {
                        if (s.espHost.isBlank()) throw IllegalStateException("Add the ESP32 address in Settings")
                        model = device?.model?.ifBlank { null } ?: s.model
                        val msgs = JSONArray()
                        if (s.persona != Persona.ASSISTANT) msgs.put(JSONObject().put("role", "system").put("content", s.persona.prompt))
                        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content.take(1500))) }
                        result = Api.espAsk(s.espHost, msgs, speak = s.route.onSpeaker, temperature = s.temperature)
                    }
                }
                val (reply, u) = result
                val msg = Message("assistant", reply.ifBlank { "(empty answer)" }, model = model, usage = u)
                putChat(chat.copy(messages = chat.messages + msg, updated = System.currentTimeMillis()))
                usage.add(UsageRecord(msg.ts, chat.id, model, u.prompt, u.completion, u.tokensPerSec, u.totalMs))
                val usageCopy = usage.toList()
                launch(Dispatchers.IO) { store.saveUsage(usageCopy) }

                // Play the answer
                if (s.route.onPhone) phoneVoice.speak(plain(reply), s.lang)
                if (s.mode == Mode.DIRECT && !s.route.onSpeaker) face("idle")
                if (s.mode == Mode.DIRECT && s.route.onSpeaker && s.espHost.isNotBlank()) {
                    try { Api.espSpeak(s.espHost, plain(reply)) } catch (e: Exception) { toast = e.message }
                }
                if (s.route.onSpeaker) refreshStatus()
                if (s.handsFree) launch { waitUntilQuiet(s); listenRequest++ }
            } catch (e: CancellationException) {
                if (s.mode == Mode.DIRECT) face("idle")
                val partial = liveText.trim()
                if (partial.isNotEmpty()) {
                    putChat(chat.copy(messages = chat.messages + Message("assistant", "$partial …", model = s.model)))
                }
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong"
                if (s.mode == Mode.DIRECT) face("error", error)
            } finally {
                busy = false
                liveText = ""
            }
        }
    }

    /** Hands-free: wait until the phone and the speaker stop talking. */
    private suspend fun waitUntilQuiet(s: AppSettings) {
        delay(600)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 90_000) {
            val phoneBusy = s.route.onPhone && phoneVoice.speaking
            val espBusy = s.route.onSpeaker && s.espHost.isNotBlank() &&
                runCatching { Api.espInfo(s.espHost).state == "speaking" }.getOrDefault(false)
            if (!phoneBusy && !espBusy) break
            delay(700)
        }
        delay(300)
    }

    /** Make the ESP32's OLED face show listening / thinking / error / idle. */
    fun face(state: String, text: String? = null) {
        val host = settings.espHost
        if (host.isBlank()) return
        viewModelScope.launch { Api.espFace(host, state, text) }
    }

    // ------------------------------------------------------------------ audio
    fun speakOnEsp(text: String) {
        val host = settings.espHost
        if (host.isBlank()) { toast = "Add the ESP32 address in Settings"; return }
        viewModelScope.launch {
            try {
                Api.espSpeak(host, plain(text))
                toast = "Playing on speaker"
                delay(300)
                refreshStatus()
            } catch (e: Exception) {
                toast = e.message
            }
        }
    }

    fun speakOnPhone(text: String) {
        if (!phoneVoice.speak(plain(text), settings.lang)) toast = "Phone voice is still starting, try again"
    }

    fun stopAudio() {
        phoneVoice.stop()
        val host = settings.espHost
        if (host.isNotBlank()) viewModelScope.launch {
            Api.espStop(host)
            delay(200)
            refreshStatus()
        }
    }

    fun setVolume(v: Float) = pushToDevice(JSONObject().put("volume", v.toDouble()), quiet = true)
    fun setEspVoice(mode: Int) = pushToDevice(JSONObject().put("voice_mode", mode), quiet = true)

    fun resetUsage() {
        usage.clear()
        viewModelScope.launch(Dispatchers.IO) { store.saveUsage(emptyList()) }
    }

    override fun onCleared() {
        phoneVoice.shutdown()
    }

    companion object {
        fun plain(s: String) = s.replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("[*_#`>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
