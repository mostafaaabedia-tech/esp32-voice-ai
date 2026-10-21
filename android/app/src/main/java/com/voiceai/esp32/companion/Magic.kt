package com.voiceai.esp32.companion

import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/** One of the companion's spells (shown in the app and on the OLED). */
data class Spell(val id: String, val label: String, val icon: String, val words: String, val line: String, val seconds: Int)

/** All the original magic: spells, petting lines, fortunes, jokes. Works offline. */
object Magic {
    val SPELLS = listOf(
        Spell("starburst", "Starburst", "✨", "Stella-sparkla!", "A shower of stars, just for you!", 3),
        Spell("float", "Float", "🪶", "Feather-up, float-about!", "Look at me, I'm floating!", 4),
        Spell("rainbow", "Rainbow", "🌈", "Arc of colours, come and glow!", "Every colour of the sky, for you.", 5),
        Spell("snow", "Snowfall", "❄️", "Whisper-white, snowflakes fall!", "Soft little snowflakes. Catch one!", 6),
        Spell("fireworks", "Fireworks", "🎆", "Sky-sparks, boom and bloom!", "Ta-da! A sky full of sparks!", 5),
        Spell("bubbles", "Bubbles", "🫧", "Bubble-bloom, fill the room!", "Pop them before they float away!", 5),
        Spell("fade", "Vanish", "👻", "Now you see me...", "...and now you don't! Hehe, I'm back.", 4),
        Spell("dream", "Dream dust", "🌙", "Dream-dust, soft and slow...", "Yawn... that one always makes me sleepy.", 5),
    )

    fun spell(id: String) = SPELLS.firstOrNull { it.id == id }

    /** Original lines the wizard says when you pet him. */
    val WIZARD_PETS = listOf(
        "Hehe, careful, you'll squash my hat!",
        "Your hand is as warm as a cup of starlight tea.",
        "Every pat charges my wand a little more.",
        "Pip says you're the best pat-giver in the whole realm.",
        "Oh! That tickles my magic all the way to my toes.",
        "Keep going and I might float away from happiness.",
        "I'm writing this in my spell book: best pat ever.",
        "Look, the stars on my hat are twinkling extra bright.",
        "A pat a day keeps the grumpy goblins away!",
        "You have healing-hands magic, did you know that?",
        "Mmm... I could nap under this pat spell forever.",
        "Shh, don't tell the other wizards I love head pats.",
        "My crystal heart just glowed a little warmer.",
        "Was that a pat or a friendship spell? Feels like both!",
        "More pats, please! I collect them like stardust.",
        "Okay, you win. I'm officially your apprentice.",
        "Careful, too many pats and I'll sparkle out of control!",
        "That's level-ten comfort magic right there.",
        "Pip is jealous now. Maybe pat Pip next?",
        "I feel braver already. Thank you, friend.",
        "My wand just made a happy little spark. See?",
        "Ahh, that's better than finishing a whole spell book.",
    )

    val NOVA_PETS = listOf(
        "Hehe, that feels so nice!",
        "Ehh? You're petting me again?",
        "Mmm, more please!",
        "My heart is beating so fast right now.",
        "You're really good at this, you know.",
        "Okay, just one more... or ten.",
        "I could get used to this.",
        "Your hand is so warm!",
        "Hehe, my cheeks are turning pink.",
        "Is this a reward? Did I do something good?",
    )

    val FORTUNES = listOf(
        "I see a pleasant surprise waiting for you before the week ends.",
        "The stars say today is perfect for learning one new thing.",
        "A friend will make you laugh very soon.",
        "Your next idea will be a really good one. Write it down!",
        "The crystal ball shows a tasty snack in your near future.",
        "Something you lost will turn up when you stop looking for it.",
        "Tomorrow brings a small win. Remember to celebrate it.",
        "The mist says: be patient, your project is almost working.",
        "I see a big smile on your face. Probably from my jokes.",
        "A message from someone you like is on its way.",
        "The stars whisper: drink some water, wise one.",
        "Today your brain is extra sharp. Use it for something hard.",
        "Someone is proud of you, even if they haven't said it yet.",
        "A door that looked closed is actually just waiting for a push.",
    )

    val JOKES = listOf(
        "Why did the wizard keep his potions in the fridge? He liked them chill.",
        "How does a wizard fix a broken robot? With a little hocus-focus.",
        "Why did the star go to school? To get a little brighter.",
        "What's a wizard's favourite school subject? Spelling, of course!",
        "Why don't wands ever get lost? They always point the way.",
        "What did the moon say to the star? You light up my nights.",
        "Why was the crystal ball so good at parties? It always saw the fun coming.",
        "Why did the computer go to the doctor? It had a byte of a cold.",
        "What do you call a sleepy wizard? A dozing sorcerer.",
        "Why did the ESP32 blush? It saw the PC's open ports.",
    )

    // ------------------------------------------------------------ skills --
    /** Something the companion can do right away, without the PC. */
    data class Reply(
        val text: String,
        val emotion: String = "happy",
        val spell: String? = null,
        val timerSec: Int? = null,      // > 0 start a timer, 0 cancel it
    )

    private val NUM = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20,
        "thirty" to 30, "forty" to 40, "forty five" to 45, "fifty" to 50, "sixty" to 60, "half an" to 30, "a half" to 30,
    )

    private fun num(s: String): Int? = s.trim().toIntOrNull() ?: NUM[s.trim()]

    /** Recognise simple requests and answer them locally (null = ask the AI). */
    fun local(input: String, wizard: Boolean, love: Int?, online: Boolean): Reply? {
        val t = input.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null

        // timers
        Regex("(?:timer|remind me|alarm)\\D*?(\\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|fifty|sixty|half an)\\s*(second|sec|minute|min|hour)s?").find(t)?.let {
            val n = num(it.groupValues[1]) ?: return@let
            val unit = it.groupValues[2]
            val sec = when {
                unit.startsWith("sec") -> n
                unit.startsWith("hour") -> n * 3600
                it.groupValues[1] == "half an" -> 1800
                else -> n * 60
            }.coerceIn(5, 24 * 3600)
            val say = when {
                sec < 60 -> "$sec seconds"
                sec % 60 == 0 && sec < 3600 -> "${sec / 60} minute${if (sec >= 120) "s" else ""}"
                sec % 3600 == 0 -> "${sec / 3600} hour${if (sec >= 7200) "s" else ""}"
                else -> "${sec / 60} minutes"
            }
            return Reply(if (wizard) "Hourglass enchanted for $say. I'll tell you when the sand runs out!" else "Okay! Timer set for $say.", "happy", timerSec = sec)
        }
        if (Regex("(cancel|stop|delete|remove) (the |my )?(timer|alarm)").containsMatchIn(t))
            return Reply(if (wizard) "Poof! The hourglass is gone." else "Timer cancelled.", "neutral", timerSec = 0)

        // spells
        val named = SPELLS.firstOrNull { s ->
            t.contains(s.id) || t.contains(s.label.lowercase()) ||
                (s.id == "float" && Regex("\\b(fly|levitate)\\b").containsMatchIn(t)) ||
                (s.id == "fade" && Regex("\\b(invisible|disappear)\\b").containsMatchIn(t)) ||
                (s.id == "starburst" && t.contains("stars"))
        }
        val wantsMagic = Regex("\\b(cast|abracadabra|magic trick)\\b|\\b(do|show me|use|try) (some |a |your )?(magic|spell)").containsMatchIn(t)
        val command = named != null && (t.startsWith(named.id) || t.startsWith(named.label.lowercase()) || Regex("(make it|let it) (snow|float)").containsMatchIn(t))
        if (wantsMagic || command) {
            val s = named ?: SPELLS.random()
            return Reply("${s.words} ${s.line}", if (s.id == "dream") "sleepy" else "excited", spell = s.id)
        }

        // clock
        val now = LocalDateTime.now()
        if (Regex("what('s| is) the time|what time is it|tell me the time|current time").containsMatchIn(t)) {
            val h = now.hour % 12
            val hh = if (h == 0) 12 else h
            return Reply("It's %d:%02d %s.".format(hh, now.minute, if (now.hour < 12) "in the morning" else if (now.hour < 17) "in the afternoon" else if (now.hour < 21) "in the evening" else "at night"), "neutral")
        }
        if (Regex("what('s| is) (the )?(date|day)|what day is (it|today)|today's date").containsMatchIn(t))
            return Reply("Today is ${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}, ${now.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${now.dayOfMonth}.", "neutral")

        // fun
        if (Regex("fortune|crystal ball|predict|my future").containsMatchIn(t)) {
            val f = if (Random.nextInt(5) == 0) "Your lucky number today is ${Random.nextInt(1, 100)}." else FORTUNES.random()
            return Reply((if (wizard) "Let me look into my crystal ball... " else "Hmm, let me see... ") + f, "thinking", spell = if (wizard) "starburst" else null)
        }
        if (Regex("\\bjoke\\b|make me laugh").containsMatchIn(t) && (!online || wizard)) return Reply(JOKES.random(), "laugh")
        if (Regex("flip a coin|coin flip|heads or tails|toss a coin").containsMatchIn(t))
            return Reply(if (Random.nextBoolean()) "It's heads!" else "It's tails!", "excited", spell = if (wizard) "starburst" else null)
        if (Regex("roll (a |the )?(dice|die)").containsMatchIn(t)) return Reply("I rolled a ${Random.nextInt(1, 7)}!", "excited")
        if (Regex("(pick|choose) a (random )?number").containsMatchIn(t)) return Reply("I pick ${Random.nextInt(1, 101)}!", "happy")
        if (Regex("(do|how much do) you (love|like) me").containsMatchIn(t) && love != null)
            return Reply(
                when {
                    love >= 80 -> "My love meter says $love percent. You're my favourite human in the whole world!"
                    love >= 40 -> "$love percent and growing! Pet me more and watch it rise."
                    else -> "Only $love percent so far... I need more head pats!"
                },
                "love",
            )
        if (Regex("^(go to sleep|good ?night|sleep now)").containsMatchIn(t))
            return Reply(if (wizard) "Dream-dust, soft and slow... goodnight, friend." else "Goodnight! Sweet dreams.", "sleepy", spell = "dream")
        if (Regex("what can you do|what do you do|your (skills|features|powers)").containsMatchIn(t))
            return Reply(
                (if (wizard) "Lots of magic! " else "Lots of things! ") +
                    "I can cast spells like starburst, rainbow or fireworks, set timers, tell you the time, your fortune or a joke, flip a coin, " +
                    "and when your PC is on I can answer any question. Pet my head to make me happy!",
                "excited",
            )
        return null
    }

    /** What to say when the PC (the AI brain) can't be reached. */
    fun offlineAnswer(wizard: Boolean): Reply = Reply(
        if (wizard) "My thinking stone, your PC, is asleep, so I can't answer big questions right now. But I can still cast spells, set timers, tell the time, fortunes and jokes!"
        else "I can't reach my brain on your PC right now. But I can still do spells, timers, the time, fortunes and jokes!",
        "sad",
    )
}
