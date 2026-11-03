package com.voiceai.esp32.companion

import com.voiceai.esp32.data.EnglishLevel

/**
 * English helper for learners whose first language is Dari: practice sentences,
 * words of the day (with Dari meanings) and pronunciation scoring. All offline.
 */
object English {
    /** "Repeat after me" sentences for each level. */
    private val PRACTICE = mapOf(
        EnglishLevel.BEGINNER to listOf(
            "Good morning, how are you?",
            "My name is Ali and I am a student.",
            "I like green tea with sugar.",
            "Where is the library, please?",
            "I live in Herat with my family.",
            "Can you help me, please?",
            "Today the weather is very nice.",
            "I go to school every day.",
            "This is my brother and this is my sister.",
            "How much is this book?",
            "I am hungry. Let's eat lunch.",
            "Please speak slowly.",
            "I don't understand. Can you say it again?",
            "Thank you very much for your help.",
            "See you tomorrow, my friend.",
        ),
        EnglishLevel.INTERMEDIATE to listOf(
            "I would like to improve my English every day.",
            "Could you tell me how to get to the bus station?",
            "Yesterday I visited my grandparents in the village.",
            "In my free time I enjoy reading and playing football.",
            "I am studying physics because I love science.",
            "What time does the class start tomorrow?",
            "I think learning a new language takes patience.",
            "Can I borrow your pen for a minute?",
            "My favourite season is spring because everything is green.",
            "We are planning a small trip next weekend.",
            "I'm sorry I'm late. The traffic was terrible.",
            "Would you like a cup of tea or coffee?",
            "The robot on my desk can see and hear me.",
            "I have been working on this project for two weeks.",
            "Please send me the homework when you are free.",
        ),
        EnglishLevel.ADVANCED to listOf(
            "Although it was raining, we decided to continue our journey.",
            "If I had more time, I would build a bigger robot.",
            "Technology can help people solve real problems in their communities.",
            "The experiment didn't work at first, but we learned a lot from our mistakes.",
            "I'm looking forward to presenting our project at the exhibition.",
            "Could you explain the difference between velocity and speed?",
            "Reading every day has gradually improved my vocabulary.",
            "Our team worked together to finish the project before the deadline.",
            "Clean water is one of the most important resources for any country.",
            "It's important to stay calm and think carefully under pressure.",
        ),
    )

    fun practiceSentence(level: EnglishLevel, avoid: String?): String =
        PRACTICE.getValue(level).filter { it != avoid }.random()

    /** A useful word with a simple meaning, a Dari meaning and an example. */
    data class Word(val word: String, val meaning: String, val dari: String, val example: String)

    val WORDS = listOf(
        Word("grateful", "thankful", "سپاسگزار، ممنون", "I am grateful for your help."),
        Word("curious", "wanting to know or learn something", "کنجکاو", "Children are curious about everything."),
        Word("patient", "able to wait calmly", "صبور، با حوصله", "A good teacher is patient."),
        Word("brave", "not afraid", "شجاع، دلیر", "She was brave and spoke in front of the class."),
        Word("improve", "to make something better", "بهتر کردن، بهبود دادن", "I practise every day to improve my English."),
        Word("borrow", "to take something and give it back later", "قرض گرفتن", "Can I borrow your book?"),
        Word("neighbour", "a person who lives next to you", "همسایه", "Our neighbour has a big garden."),
        Word("healthy", "good for your body, not sick", "صحتمند، سالم", "Fruit is healthy food."),
        Word("explain", "to make something clear", "توضیح دادن", "Can you explain this question?"),
        Word("journey", "a long trip", "سفر", "The journey to Kabul was long."),
        Word("honest", "always telling the truth", "صادق، راستگو", "He is an honest boy."),
        Word("difficult", "not easy", "مشکل، دشوار", "This exercise is difficult."),
        Word("celebrate", "to do something special on a happy day", "جشن گرفتن", "We celebrate Eid with our family."),
        Word("invite", "to ask someone to come", "دعوت کردن", "I want to invite you to my house."),
        Word("remember", "to keep something in your mind", "به یاد داشتن، یادم بودن", "Do you remember my name?"),
        Word("weather", "rain, sun, wind, cold or hot outside", "آب و هوا", "The weather is cold today."),
        Word("careful", "paying attention to avoid problems", "محتاط، با احتیاط", "Be careful, the road is busy."),
        Word("library", "a place with many books to read", "کتابخانه", "I study in the library."),
        Word("delicious", "very tasty", "خوشمزه، مزه‌دار", "My mother's food is delicious."),
        Word("polite", "kind and respectful in how you speak", "مؤدب", "It is polite to say thank you."),
        Word("skill", "something you can do well", "مهارت", "Coding is a useful skill."),
        Word("goal", "something you want to do in the future", "هدف", "My goal is to become an engineer."),
        Word("protect", "to keep safe", "محافظت کردن", "We must protect the environment."),
        Word("deliver", "to bring something to someone", "رساندن، تحویل دادن", "The shop will deliver the bread."),
        Word("encourage", "to give someone hope and support", "تشویق کردن", "My father encourages me to study."),
        Word("discover", "to find something new", "کشف کردن", "Scientists discover new things every year."),
        Word("explore", "to travel or look around to learn", "گشت و گذار کردن، کاوش کردن", "Let's explore the old city."),
        Word("experiment", "a test to learn something in science", "آزمایش", "We did an experiment in the physics lab."),
        Word("energy", "the power to do work or move", "انرژی", "The sun gives us energy."),
        Word("community", "people who live in the same place", "جامعه، اجتماع", "Our community built a new school."),
    )

    fun wordOfTheDay(dayOfYear: Int): Word = WORDS[dayOfYear % WORDS.size]

    /** How well you repeated a sentence: score 0..100 and which words were missed. */
    data class Score(val percent: Int, val words: List<Pair<String, Boolean>>)

    private fun norm(w: String) = w.lowercase().replace(Regex("[^a-z0-9']"), "")
        .replace("'m", " am").replace("n't", " not").replace("'re", " are").replace("'s", "").replace("'ll", " will")

    private fun tokens(s: String) = s.split(Regex("\\s+")).flatMap { norm(it).split(" ") }.filter { it.isNotBlank() }

    /** Compares what you said with the sentence (longest common word sequence). */
    fun score(target: String, said: String): Score {
        val shown = target.split(Regex("\\s+")).filter { it.isNotBlank() }
        val t = shown.map { tokens(it).joinToString(" ") }
        val s = tokens(said)
        // LCS over words, allowing small spelling differences from the recognizer
        fun same(a: String, b: String) = a == b || (a.length > 3 && b.length > 3 && (a.startsWith(b.take(4)) || b.startsWith(a.take(4))))
        val tw = t.map { it.split(" ").first() }
        val dp = Array(tw.size + 1) { IntArray(s.size + 1) }
        for (i in tw.indices.reversed()) for (j in s.indices.reversed())
            dp[i][j] = if (same(tw[i], s[j])) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val ok = BooleanArray(tw.size)
        var i = 0
        var j = 0
        while (i < tw.size && j < s.size) {
            when {
                same(tw[i], s[j]) -> { ok[i] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        val pct = if (tw.isEmpty()) 0 else ok.count { it } * 100 / tw.size
        return Score(pct, shown.mapIndexed { k, w -> w to ok[k] })
    }

    /** Spoken feedback after a practice try. */
    fun feedback(sc: Score, wizard: Boolean): String {
        val missed = sc.words.filter { !it.second }.map { it.first.trim(',', '.', '?', '!') }.take(3)
        return when {
            sc.percent >= 90 -> listOf("Excellent! That was perfect.", "Wonderful! Very clear.", if (wizard) "Magic! You said it perfectly." else "Perfect! Great job.").random()
            sc.percent >= 70 -> "Very good! Practise these words: ${missed.joinToString(", ")}."
            sc.percent >= 40 -> "Good try! Listen again and say it slowly. Watch: ${missed.joinToString(", ")}."
            else -> "Let's try again together. Listen carefully and speak slowly."
        }
    }
}
