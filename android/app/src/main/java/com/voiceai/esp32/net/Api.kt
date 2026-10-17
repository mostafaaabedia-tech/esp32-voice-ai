package com.voiceai.esp32.net

import com.voiceai.esp32.data.DeviceInfo
import com.voiceai.esp32.data.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

class ApiException(message: String) : Exception(message)

/** Plain HttpURLConnection client: no extra libraries needed. */
object Api {
    @Volatile private var streamConn: HttpURLConnection? = null

    private fun base(host: String): String {
        val h = host.trim().trimEnd('/')
        return if (h.startsWith("http://") || h.startsWith("https://")) h else "http://$h"
    }

    /** Ollama listens on 11434 unless the user typed a port. */
    private fun ollamaBase(host: String): String {
        val b = base(host)
        return if (Regex(":\\d+$").containsMatchIn(b)) b else "$b:11434"
    }

    private suspend fun request(
        method: String, url: String, body: JSONObject? = null, timeoutMs: Int = 8000,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            c.disconnect()
        }
    }

    private fun errorOf(text: String, code: Int): String =
        runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: "HTTP $code"

    // ================================================================ ESP32 ===
    suspend fun espInfo(host: String): DeviceInfo {
        val (code, text) = try {
            request("GET", base(host) + "/api/info", timeoutMs = 4000)
        } catch (e: IOException) {
            throw ApiException("Can't reach the ESP32 at $host")
        }
        if (code != 200) throw ApiException(errorOf(text, code))
        val j = JSONObject(text)
        val c = j.optJSONObject("config") ?: JSONObject()
        val s = j.optJSONObject("session") ?: JSONObject()
        val m = j.optJSONArray("models") ?: JSONArray()
        return DeviceInfo(
            ip = j.optString("ip"), fw = j.optString("fw"), rssi = j.optInt("rssi"),
            heap = j.optLong("heap"), uptimeSec = j.optLong("uptime_s"), state = j.optString("state"),
            ollamaOnline = j.optBoolean("ollama_online"),
            models = (0 until m.length()).map { m.getString(it) },
            ollamaHost = c.optString("ollama_host"), model = c.optString("model"),
            voiceMode = c.optInt("voice_mode"), volume = c.optDouble("volume", 2.0).toFloat(),
            numCtx = c.optInt("num_ctx"),
            sessionRequests = s.optInt("requests"), sessionPrompt = s.optLong("prompt_tokens"),
            sessionCompletion = s.optLong("completion_tokens"),
        )
    }

    suspend fun espConfig(host: String, patch: JSONObject) {
        val (code, text) = try {
            request("POST", base(host) + "/api/config", patch)
        } catch (e: IOException) {
            throw ApiException("Can't reach the ESP32 at $host")
        }
        if (code != 200) throw ApiException(errorOf(text, code))
    }

    suspend fun espSpeak(host: String, text: String) {
        val (code, body) = try {
            request("POST", base(host) + "/api/speak", JSONObject().put("text", text))
        } catch (e: IOException) {
            throw ApiException("Speaker not reachable at $host")
        }
        if (code != 200) throw ApiException(errorOf(body, code))
    }

    suspend fun espStop(host: String) {
        runCatching { request("POST", base(host) + "/api/stop", JSONObject(), 3000) }
    }

    /** Ask through the ESP32 (the original way). Polls until the answer is ready. */
    suspend fun espAsk(host: String, messages: JSONArray, speak: Boolean): Pair<String, Usage> {
        val body = JSONObject().put("messages", messages).put("speak", speak)
        val (code, text) = try {
            request("POST", base(host) + "/api/ask", body)
        } catch (e: IOException) {
            throw ApiException("Can't reach the ESP32 at $host")
        }
        if (code == 409) throw ApiException("The ESP32 is still busy with another question.")
        if (code != 202 && code != 200) throw ApiException(errorOf(text, code))
        val id = JSONObject(text).getInt("id")

        val start = System.currentTimeMillis()
        var misses = 0
        while (true) {
            delay(400)
            coroutineContext.ensureActive()
            if (System.currentTimeMillis() - start > 150_000) throw ApiException("No answer after 150 s. Is Ollama running?")
            val st = try {
                JSONObject(request("GET", base(host) + "/api/status", timeoutMs = 5000).second)
            } catch (e: Exception) {
                if (++misses > 10) throw ApiException("Lost connection to the ESP32")
                continue
            }
            misses = 0
            if (st.optInt("done_id") >= id) {
                val err = st.optString("error")
                if (err.isNotBlank()) throw ApiException(err)
                val u = st.optJSONObject("usage") ?: JSONObject()
                return st.optString("reply") to Usage(
                    prompt = u.optInt("prompt_tokens"),
                    completion = u.optInt("completion_tokens"),
                    tokensPerSec = u.optDouble("tokens_per_sec", 0.0),
                    totalMs = u.optLong("total_ms", System.currentTimeMillis() - start),
                    loadMs = u.optLong("load_ms"),
                )
            }
        }
    }

    // ================================================================ Ollama ==
    suspend fun ollamaModels(host: String): List<String> {
        val (code, text) = try {
            request("GET", ollamaBase(host) + "/api/tags", timeoutMs = 2500)
        } catch (e: IOException) {
            throw ApiException("Can't reach Ollama at $host")
        }
        if (code != 200) throw ApiException(errorOf(text, code))
        val a = JSONObject(text).optJSONArray("models") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).optString("name") }
    }

    /**
     * Direct chat with Ollama, streamed word by word.
     * [onToken] gets each new piece of text as it arrives.
     */
    suspend fun ollamaChat(
        host: String, model: String, messages: JSONArray, maxTokens: Int, onToken: suspend (String) -> Unit,
    ): Pair<String, Usage> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("keep_alive", "30m")
            .put("messages", messages)
            .put("options", JSONObject().put("num_predict", maxTokens))   // no num_ctx: avoids engine restarts

        val start = System.currentTimeMillis()
        val c = try {
            (URL(ollamaBase(host) + "/api/chat").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        } catch (e: IOException) {
            throw ApiException("Bad PC address: $host")
        }
        streamConn = c
        try {
            try {
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            } catch (e: IOException) {
                throw ApiException("Can't reach Ollama at $host. Is start_ollama_lan.bat running on the PC?")
            }
            val code = c.responseCode
            if (code != 200) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw ApiException("Ollama: " + errorOf(err, code))
            }
            val full = StringBuilder()
            var usage = Usage(0, 0, 0.0, 0)
            c.inputStream.bufferedReader().use { reader ->
                while (true) {
                    ensureActive()
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val j = JSONObject(line)
                    if (j.has("error")) throw ApiException("Ollama: " + j.getString("error"))
                    val piece = j.optJSONObject("message")?.optString("content").orEmpty()
                    if (piece.isNotEmpty()) {
                        full.append(piece)
                        onToken(piece)
                    }
                    if (j.optBoolean("done")) {
                        val evalNs = j.optLong("eval_duration")
                        val outTok = j.optInt("eval_count")
                        usage = Usage(
                            prompt = j.optInt("prompt_eval_count"),
                            completion = outTok,
                            tokensPerSec = if (evalNs > 0) outTok * 1e9 / evalNs else 0.0,
                            totalMs = System.currentTimeMillis() - start,
                            loadMs = j.optLong("load_duration") / 1_000_000,
                        )
                        break
                    }
                }
            }
            full.toString().trim() to usage
        } catch (e: IOException) {
            ensureActive()   // cancelled by the Stop button -> CancellationException
            throw ApiException("Connection to Ollama dropped: ${e.message}")
        } finally {
            c.disconnect()
            streamConn = null
        }
    }

    /** Stop button: cut the running stream. */
    fun cancelStream() {
        val c = streamConn ?: return
        Thread { runCatching { c.disconnect() } }.start()
    }
}
