package com.voiceai.esp32.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves settings, chats and usage on the phone. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("voiceai", Context.MODE_PRIVATE)
    private val chatsFile = File(context.filesDir, "chats.json")
    private val usageFile = File(context.filesDir, "usage.json")

    // ---------------------------------------------------------------- settings
    fun loadSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            espHost = prefs.getString("espHost", d.espHost) ?: d.espHost,
            pcHost = prefs.getString("pcHost", d.pcHost) ?: d.pcHost,
            mode = runCatching { Mode.valueOf(prefs.getString("mode", d.mode.name)!!) }.getOrDefault(d.mode),
            route = runCatching { Route.valueOf(prefs.getString("route", d.route.name)!!) }.getOrDefault(d.route),
            lang = prefs.getString("lang", d.lang) ?: d.lang,
            autoSend = prefs.getBoolean("autoSend", d.autoSend),
            history = prefs.getInt("history", d.history),
            model = prefs.getString("model", d.model) ?: d.model,
            systemPrompt = prefs.getString("systemPrompt", d.systemPrompt) ?: d.systemPrompt,
            maxWords = prefs.getInt("maxWords", d.maxWords),
            maxTokens = prefs.getInt("maxTokens", d.maxTokens),
        )
    }

    fun saveSettings(s: AppSettings) {
        prefs.edit()
            .putString("espHost", s.espHost)
            .putString("pcHost", s.pcHost)
            .putString("mode", s.mode.name)
            .putString("route", s.route.name)
            .putString("lang", s.lang)
            .putBoolean("autoSend", s.autoSend)
            .putInt("history", s.history)
            .putString("model", s.model)
            .putString("systemPrompt", s.systemPrompt)
            .putInt("maxWords", s.maxWords)
            .putInt("maxTokens", s.maxTokens)
            .apply()
    }

    var currentChatId: String?
        get() = prefs.getString("current", null)
        set(v) = prefs.edit().putString("current", v).apply()

    // ------------------------------------------------------------------- chats
    fun loadChats(): List<Chat> = runCatching {
        val a = JSONArray(chatsFile.readText())
        (0 until a.length()).map { chatFrom(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveChats(chats: List<Chat>) {
        val a = JSONArray()
        chats.take(80).forEach { a.put(chatTo(it)) }
        runCatching { chatsFile.writeText(a.toString()) }
    }

    // ------------------------------------------------------------------- usage
    fun loadUsage(): List<UsageRecord> = runCatching {
        val a = JSONArray(usageFile.readText())
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            UsageRecord(
                o.getLong("ts"), o.optString("chat"), o.optString("model"),
                o.optInt("p"), o.optInt("c"), o.optDouble("tps", 0.0), o.optLong("ms"),
            )
        }
    }.getOrDefault(emptyList())

    fun saveUsage(list: List<UsageRecord>) {
        val a = JSONArray()
        list.takeLast(2000).forEach {
            a.put(
                JSONObject().put("ts", it.ts).put("chat", it.chatId).put("model", it.model)
                    .put("p", it.prompt).put("c", it.completion).put("tps", it.tokensPerSec).put("ms", it.totalMs)
            )
        }
        runCatching { usageFile.writeText(a.toString()) }
    }

    // ----------------------------------------------------------------- helpers
    private fun chatTo(c: Chat): JSONObject {
        val msgs = JSONArray()
        c.messages.forEach { m ->
            val o = JSONObject().put("r", m.role).put("t", m.content).put("ts", m.ts)
            m.model?.let { o.put("m", it) }
            m.usage?.let { u ->
                o.put("u", JSONObject().put("p", u.prompt).put("c", u.completion)
                    .put("tps", u.tokensPerSec).put("ms", u.totalMs).put("load", u.loadMs))
            }
            msgs.put(o)
        }
        return JSONObject().put("id", c.id).put("title", c.title).put("updated", c.updated).put("messages", msgs)
    }

    private fun chatFrom(o: JSONObject): Chat {
        val a = o.optJSONArray("messages") ?: JSONArray()
        val msgs = (0 until a.length()).map { i ->
            val m = a.getJSONObject(i)
            val u = m.optJSONObject("u")?.let {
                Usage(it.optInt("p"), it.optInt("c"), it.optDouble("tps", 0.0), it.optLong("ms"), it.optLong("load"))
            }
            Message(m.optString("r"), m.optString("t"), m.optLong("ts"), m.optString("m").ifEmpty { null }, u)
        }
        return Chat(o.getString("id"), o.optString("title", "Chat"), msgs, o.optLong("updated"))
    }
}
