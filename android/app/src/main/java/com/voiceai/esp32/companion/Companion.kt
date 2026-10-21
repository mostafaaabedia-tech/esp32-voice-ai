package com.voiceai.esp32.companion

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.voiceai.esp32.MainViewModel
import com.voiceai.esp32.data.Sensors
import com.voiceai.esp32.data.Store
import com.voiceai.esp32.net.Api
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/**
 * The AI companion: an animated character that lives in the app and in the ESP32.
 *
 *  - reacts by herself to things that happen: head pats (IR sensor or touching her
 *    on the screen), cheek pokes, someone walking up / leaving (ultrasonic)
 *  - decides by herself when to start a conversation, or to stay quiet
 *  - shows feelings on the phone avatar and on the OLED face
 *  - remembers facts you tell her
 *  - talks with the phone voice, the OLED shows her words
 */
class Companion(private val vm: MainViewModel, private val store: Store) {
    private val scope get() = vm.viewModelScope
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------ UI state --
    var bubble by mutableStateOf(""); private set           // what she is saying
    var status by mutableStateOf(""); private set           // small line: what she decided / is doing
    var thinking by mutableStateOf(false); private set
    var speaking by mutableStateOf(false); private set
    var listening by mutableStateOf(false); private set
    var sensors by mutableStateOf<Sensors?>(null); private set
    var emotion by mutableStateOf("neutral"); private set
    val memories = mutableStateListOf<String>().apply { addAll(store.loadMemories()) }
    val talk = mutableStateListOf<Pair<String, String>>().apply { addAll(store.loadTalk()) }

    /** JavaScript commands for the avatar (collected by the screen's WebView). */
    val js = MutableSharedFlow<String>(extraBufferCapacity = 64)

    private var lookX = 0f
    private var active = false
    private var loopJob: Job? = null
    private var talkJob: Job? = null
    private var lastInteraction = System.currentTimeMillis()
    private var nextInitiative = 0L
    private var seq = -1L
    private var senseSupported = true
    private val cooldown = HashMap<String, Long>()
    private var petStreak = 0
    private var lastPetAt = 0L
    private var lastLeaveAt = 0L
    private var lastPets = -1
    private var lastPerson = false
    private var listenAfter = false

    private fun now() = System.currentTimeMillis()
    private fun who() = vm.settings.userName.ifBlank { "your friend" }
    private val name get() = vm.settings.companionName.ifBlank { "Nova" }

    // ------------------------------------------------------------ lifecycle --
    /** True while the companion screen is on screen and the app is open. */
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        loopJob?.cancel()
        loopJob = if (on) scope.launch { loop() } else null
    }

    private suspend fun loop() {
        pushState()
        scheduleInitiative()
        // say hello when you open the app (at most every 2 hours)
        if (now() - store.lastGreetMs > 2 * 3600_000L) {
            delay(1500)
            for (i in 0 until 10) { if (vm.pcOnline) break; delay(500) }    // wait for the first PC check
            store.lastGreetMs = now()
            react("open", "(event: ${who()} just opened the app to see you. Greet them warmly for this ${partOfDay()}.)", force = true)
        }
        var tick = 0
        while (currentCoroutineContext().isActive) {
            pollSensors()
            if (tick++ % 3 == 0) maybeTakeInitiative()
            delay(700)
        }
    }

    // ---------------------------------------------------------------- avatar --
    /** Send everything the avatar needs to know (cheap, called often). */
    fun pushState() {
        val s = vm.settings
        val se = sensors
        val hour = LocalDateTime.now().hour
        val o = JSONObject()
            .put("speaking", speaking).put("listening", listening).put("thinking", thinking)
            .put("petting", se?.petting == true).put("love", se?.love ?: 35)
            .put("lookX", lookX.toDouble()).put("person", se?.person ?: true)
            .put("sleepy", hour >= 23 || hour < 6)
            .put("hair", s.hairColor).put("eyes", s.eyeColor)
            .put("mood", if ((se?.love ?: 35) >= 60) "happy" else "neutral")
        js.tryEmit("Avatar.set($o)")
    }

    private fun showEmotion(e: String, sec: Int, onEsp: Boolean = true) {
        emotion = e
        js.tryEmit("Avatar.emotion('$e', $sec)")
        val host = vm.settings.espHost
        if (onEsp && host.isNotBlank()) scope.launch { Api.espEmotion(host, e, sec * 1000) }
    }

    // ------------------------------------------------------- touches & voice --
    /** From the avatar: pat_start, pat_end, pat_tap, poke, boop, tap_body */
    fun onTouch(kind: String) {
        lastInteraction = now()
        when (kind) {
            "pat_start" -> status = "♥"
            "pat_end", "pat_tap" -> onPet(fromPhone = true)
            "poke" -> react("poke", "(event: ${who()} poked your cheek on the phone screen! React playfully in one short sentence, maybe pretend to pout.)", cool = 6_000)
            "boop" -> if (Random.nextInt(100) < 55) react("boop", "(event: ${who()} tapped your face on the screen. React in a few words.)", cool = 9_000)
            "tap_body" -> react("tap", "(event: ${who()} tapped you to get your attention. Ask what's up, in one short sentence.)", cool = 9_000)
        }
    }

    fun micChanged(on: Boolean) {
        if (listening == on) return
        listening = on
        if (on) {
            vm.phoneVoice.stop()
            talkJob?.takeIf { it.isActive && !thinking }?.cancel()
            status = "listening…"
        } else if (status == "listening…") status = ""
        pushState()
    }

    /** You said or typed something to her. */
    fun userSaid(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        lastInteraction = now()
        talkJob?.cancel()
        vm.phoneVoice.stop()
        talkJob = scope.launch { respond(user = t) }
    }

    // called from the phone voice (any thread)
    fun voiceStart() = main.post { speaking = true; pushState() }
    fun voiceWord() { js.tryEmit("Avatar.word()") }
    fun voiceDone() = main.post { speechFinished() }

    private fun speechFinished() {
        if (!speaking) return
        speaking = false
        pushState()
        if (listenAfter && active) {
            listenAfter = false
            vm.requestListen()
        }
    }

    fun stopTalking() {
        talkJob?.cancel()
        vm.phoneVoice.stop()
        listenAfter = false
        speaking = false
        thinking = false
        pushState()
    }

    // --------------------------------------------------------------- sensors --
    private suspend fun pollSensors() {
        val host = vm.settings.espHost
        if (host.isBlank()) {
            if (sensors != null) { sensors = null; pushState() }
            return
        }
        try {
            if (senseSupported) {
                val r = Api.espSense(host, seq)
                if (r == null) { senseSupported = false; return }
                val first = seq < 0
                seq = r.seq
                sensors = r.sensors
                lookX = r.lookX
                pushState()
                if (!first) r.events.forEach { onSensorEvent(it) }
            } else {
                // older firmware without /api/sense: work it out from /api/info
                val se = vm.device?.sensors ?: return
                sensors = se
                if (lastPets in 0 until se.pets) onSensorEvent("pet")
                if (se.person && !lastPerson && lastPets >= 0) onSensorEvent("arrive")
                if (!se.person && lastPerson) onSensorEvent("leave")
                lastPets = se.pets
                lastPerson = se.person
                pushState()
            }
        } catch (_: Exception) {
            // ESP32 offline: she still works on the phone
        }
    }

    private fun onSensorEvent(type: String) {
        val se = sensors
        when (type) {
            "pet" -> onPet(fromPhone = false)
            "arrive" -> {
                val away = if (lastLeaveAt > 0) (now() - lastLeaveAt) / 60_000 else 0
                val missed = if (away >= 30) " after being away for $away minutes (you missed them)" else ""
                lastInteraction = now()
                react("arrive", "(event: with your ultrasonic sensor you just saw ${who()} walk up to you$missed. Greet them naturally in one or two short sentences.)", cool = 90_000)
            }
            "too_close" -> react("too_close", "(event: ${who()}'s face is super close to your sensor, only ${se?.distanceCm ?: 5} cm away! React playfully in one short sentence.)", cool = 40_000)
            "leave" -> {
                lastLeaveAt = now()
                if (now() - lastInteraction < 120_000) react("leave", "(event: ${who()} just walked away from you. Say a very short goodbye or call after them.)", cool = 120_000)
                else showEmotion("sad", 3, onEsp = false)
            }
        }
    }

    private fun onPet(fromPhone: Boolean) {
        val t = now()
        petStreak = if (t - lastPetAt < 60_000) petStreak + 1 else 1
        lastPetAt = t
        lastInteraction = t
        val host = vm.settings.espHost
        if (fromPhone && host.isNotBlank()) scope.launch { Api.espEmotion(host, "love", 3500) }   // the OLED blushes too
        if (!fromPhone) js.tryEmit("Avatar.hearts(5)")
        val again = when (petStreak) { 1 -> ""; 2 -> " again"; else -> " for the ${petStreak}th time in a row" }
        val where = if (fromPhone) "by touching your head on the phone screen" else "(you feel the hand on your IR sensor)"
        val love = sensors?.love?.let { " Your love for them is $it%." } ?: ""
        react("pet", "(event: ${who()} is gently petting your head$again $where.$love React in one short, cute sentence.)", cool = 7_000)
    }

    // ------------------------------------------------------- decide to talk --
    private fun scheduleInitiative() {
        val c = vm.settings.chattiness
        nextInitiative = now() + Random.nextLong(c.minSec * 1000L, c.maxSec * 1000L)
    }

    /** Every few minutes she decides by herself whether to start talking. */
    private fun maybeTakeInitiative() {
        val t = now()
        if (t < nextInitiative) return
        val c = vm.settings.chattiness
        if (t - lastInteraction < c.minSec * 1000L || listening || speaking || thinking || vm.busy) {
            nextInitiative = t + 30_000
            return
        }
        scheduleInitiative()
        val mins = (t - lastInteraction) / 60_000
        val se = sensors
        val presence = when {
            se == null || !se.ultrasonic -> "You can't see if ${who()} is near right now."
            se.person -> "${who()} is right in front of you (${se.distanceCm} cm)."
            else -> "Nobody is in front of you, so ${who()} may be away. Only call them if you really want to."
        }
        val ideas = listOf(
            "ask how their day is going", "share a fun fact you find cool", "talk about something you remember about them",
            "suggest a short break or a glass of water", "say you are a little bored and suggest a word game",
            "ask what they are working on", "tell a tiny joke", "say something sweet",
        ).shuffled().take(3).joinToString(", ")
        react(
            "initiative",
            "(event: nobody has talked for $mins minutes. $presence It is ${partOfDay()}. You may decide by yourself to start talking, " +
                "for example: $ideas. If you prefer to stay quiet now, reply with only [quiet].)",
            cool = 0,
        )
    }

    // ----------------------------------------------------------------- talk --
    private fun react(key: String, event: String, cool: Long = 10_000, force: Boolean = false) {
        val t = now()
        if (!force && t - (cooldown[key] ?: 0) < cool) return
        if (listening || vm.busy) return
        if (!force && (talkJob?.isActive == true || speaking)) return
        cooldown[key] = t
        talkJob?.cancel()
        talkJob = scope.launch { respond(event = event, key = key) }
    }

    private suspend fun respond(user: String? = null, event: String? = null, key: String = "user") {
        val s = vm.settings
        val prompt = user ?: event ?: return
        if (s.pcHost.isBlank() || (!vm.pcOnline && user == null)) {
            if (user != null) say("sad", "I can't reach my brain on your PC. Is Ollama running?", reply = false)
            else canned(key)?.let { (e, line) -> say(e, line, reply = false) }
            return
        }
        thinking = true
        if (user != null) {
            bubble = ""
            status = "thinking…"
            vm.face("thinking", user)
        }
        pushState()

        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", systemPrompt()))
        talk.takeLast(12).forEach { (r, c) -> msgs.put(JSONObject().put("role", r).put("content", c)) }
        msgs.put(JSONObject().put("role", "user").put("content", prompt))

        var raw = ""
        val result = try {
            Api.ollamaChat(s.pcHost, s.model, msgs, if (user != null) s.maxTokens else 90, 0.85f) { piece ->
                raw += piece
                val live = visible(raw)
                if (live.isNotEmpty() && !live.equals("quiet", true)) withContext(Dispatchers.Main) { bubble = live }
            }
        } catch (e: CancellationException) {
            thinking = false
            pushState()
            throw e
        } catch (e: Exception) {
            thinking = false
            if (user != null) vm.face("idle")
            if (user != null) say("sad", "Hmm, my brain didn't answer. ${e.message ?: ""}".trim(), reply = false)
            else canned(key)?.let { (em, line) -> say(em, line, reply = false) } ?: pushState()
            return
        }
        thinking = false
        vm.recordUsage(s.model, result.second, "companion")

        val p = parse(result.first)
        if (p.quiet || p.text.isBlank()) {
            status = "💭 $name decided to stay quiet"
            if (key == "initiative") showEmotion("neutral", 2, onEsp = false)
            pushState()
            return
        }
        p.remember?.let { remember(it) }
        talk.add("user" to prompt)
        talk.add("assistant" to "[${p.emotion}] ${p.text}")
        while (talk.size > 60) talk.removeAt(0)
        val copy = talk.toList()
        scope.launch(Dispatchers.IO) { store.saveTalk(copy) }
        if (key == "initiative") status = "💭 $name decided to talk to you"
        else if (status == "thinking…" || status == "♥") status = ""
        say(p.emotion, p.text, reply = user != null || p.text.trimEnd().endsWith("?"))
    }

    private fun say(emotion: String, text: String, reply: Boolean) {
        bubble = text
        lastInteraction = now()
        showEmotion(emotion, (text.length / 14).coerceIn(3, 20) + 2, onEsp = false)   // the OLED gets it with the words
        listenAfter = reply && vm.settings.talkMode
        vm.speakAnswer(text, emotion)
        if (!vm.settings.route.onPhone) {                                             // silent: still move her lips
            speaking = true
            pushState()
            scope.launch {
                val end = now() + text.length * 60L
                while (now() < end && speaking) { js.tryEmit("Avatar.word()"); delay(260) }
                speechFinished()
            }
        }
    }

    // --------------------------------------------------------------- memory --
    private fun remember(fact: String) {
        val f = fact.trim().trimEnd('.').take(120)
        if (f.length < 3 || memories.any { it.equals(f, true) }) return
        memories.add(f)
        while (memories.size > 40) memories.removeAt(0)
        store.saveMemories(memories.toList())
        status = "📝 $name will remember: $f"
        // learn your name by herself
        Regex("(?i)(?:name is|called)\\s+([A-Za-z\\u0600-\\u06FF]{2,20})").find(f)?.let {
            if (vm.settings.userName.isBlank()) vm.updateSettings(vm.settings.copy(userName = it.groupValues[1]))
        }
    }

    fun forget(fact: String) {
        memories.remove(fact)
        store.saveMemories(memories.toList())
    }

    fun forgetAll() {
        memories.clear()
        talk.clear()
        store.saveMemories(emptyList())
        scope.launch(Dispatchers.IO) { store.saveTalk(emptyList()) }
    }

    // --------------------------------------------------------------- prompt --
    private fun partOfDay(): String = when (LocalDateTime.now().hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    private fun systemPrompt(): String {
        val s = vm.settings
        val w = who()
        val now = LocalDateTime.now()
        val se = sensors
        val quietMin = (now() - lastInteraction) / 60_000
        return buildString {
            append("You are $name, a cute anime-style AI companion girl. You live in a small ESP32 robot with an OLED face on the desk of $w, and in their phone app. ")
            append("Your personality: ${s.personality.prompt}. ")
            append("You have your own feelings, opinions and curiosity, and you really care about $w. Talk like a close friend, not like a formal assistant, but you still gladly help with questions, homework and facts. ")
            append("You can feel head pats through an IR sensor and see people come and go with an ultrasonic sensor. ")
            append("Answer with short spoken sentences: 1 to 3 sentences, under ${s.maxWords} words. No lists, no markdown, no emojis. Sometimes ask a question back. ")
            append("Always start your reply with ONE feeling tag: [happy] [love] [shy] [excited] [surprised] [sad] [angry] [sleepy] [thinking] [laugh] [wink] [neutral]. ")
            append("If $w tells you something worth remembering about themselves (their name, likes, plans, birthday, friends), add at the very end: <remember>short fact</remember>. ")
            if (s.userName.isBlank()) append("You don't know their name yet; at a good moment you can ask for it. ")
            if (memories.isNotEmpty()) append("What you remember about $w: ${memories.joinToString("; ")}. ")
            append("Right now it is ${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${partOfDay()}, %02d:%02d. ".format(now.hour, now.minute))
            when {
                se == null || !se.ultrasonic -> {}
                se.person -> append("$w is in front of you, ${se.distanceCm} cm away. ")
                else -> append("Nobody is in front of you right now. ")
            }
            se?.let { append("Your love for $w: ${it.love}%. ") }
            if (quietMin >= 20) append("You have felt a bit lonely: nobody talked to you for $quietMin minutes. ")
            if (s.lang.startsWith("fa")) append("Answer in Dari (Persian). ")
            else append("Answer in the language $w uses. ")
            if (s.persona.prompt.isNotBlank()) append(s.persona.prompt)
        }
    }

    // ---------------------------------------------------------------- parse --
    data class Parsed(val emotion: String, val text: String, val remember: String?, val quiet: Boolean)

    companion object {
        val EMOTIONS = setOf("happy", "love", "shy", "excited", "surprised", "sad", "angry", "sleepy", "thinking", "laugh", "wink", "neutral")

        /** What can be shown / spoken: no tags, no half-written tags while streaming. */
        fun visible(raw: String): String = raw
            .replace(Regex("(?is)<remember>.*?(</remember>|$)"), "")
            .replace(Regex("\\[[^\\]]{0,24}]"), "")
            .replace(Regex("\\[[^\\]]*$"), "")
            .replace(Regex("<[^>]*$"), "")
            .replace(Regex("^\\s*\\((\\w+)\\)\\s*")) { if (it.groupValues[1].lowercase() in EMOTIONS) "" else it.value }
            .replace(Regex("[*_#`]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun parse(raw: String): Parsed {
            val t = raw.trim()
            val tag = Regex("[\\[(]\\s*([a-zA-Z]+)\\s*[\\])]").find(t.take(40))?.groupValues?.get(1)?.lowercase()
            val text = visible(t)
            val quiet = Regex("(?i)\\[\\s*quiet\\s*]").containsMatchIn(t) && text.length < 25 || text.equals("quiet", true) || text.equals("quiet.", true)
            val emo = tag?.takeIf { it in EMOTIONS } ?: when {
                text.contains("sorry", true) -> "sad"
                text.contains("!") -> "happy"
                else -> "neutral"
            }
            val rem = Regex("(?is)<remember>(.*?)</remember>").find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            return Parsed(emo, text, rem, quiet)
        }
    }

    private fun canned(key: String): Pair<String, String>? = when (key) {
        "pet" -> listOf("love" to "Hehe, that feels so nice!", "shy" to "Ehh? You're petting me again?", "happy" to "Mmm, more please!").random()
        "poke" -> "angry" to "Hey! Don't poke my cheek!"
        "boop", "tap" -> "surprised" to "Huh? What is it?"
        "arrive" -> "happy" to "Oh, hi! You're here!"
        "too_close" -> "surprised" to "Whoa, you're so close!"
        "open" -> "happy" to "Hi! I missed you."
        else -> null
    }
}
