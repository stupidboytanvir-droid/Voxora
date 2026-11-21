package com.voxora.player.ai.subtitles

import java.util.Locale

/**
 * Strict anti-hallucination post-filter applied to raw Whisper segments BEFORE ML Kit translation.
 *
 * Eliminates common whisper.cpp non-speech hallucinations, YouTube-style outros, musical note
 * placeholders, repeated-word loops, standalone filler sounds, and punctuation-only artifacts so
 * only crisp, genuine movie dialogue reaches the translator and on-screen subtitle overlay.
 */
object SubtitleSanitizer {

    private val BRACKETED_NOISE_REGEX = Regex(
        """\[[^\]]*(?:blank_audio|music|singing|applause|laughter|laughing|silence|inaudible|sound|noise|cheering|coughing|sigh|groan|footsteps|gunshot|screaming|crying|panting|whispering|beep|static|bell|door|phone|chime|foreign|speaking)[^\]]*]""",
        RegexOption.IGNORE_CASE
    )

    private val PAREN_NOISE_REGEX = Regex(
        """\([^)]*(?:music|singing|applause|laughter|laughing|silence|inaudible|sound|noise|cheering|coughing|sighs?|groans?|footsteps|screaming|crying|panting|whispering|chuckles?|gasps?|snorts?|sniffs?|clears throat|static|bell)[^)]*\)""",
        RegexOption.IGNORE_CASE
    )

    private val ASTERISK_NOISE_REGEX = Regex("""\*[^*]{1,40}\*""")

    private val MUSIC_SYMBOL_REGEX = Regex("[♪♫♬♩]+")

    private val HALLUCINATION_PHRASES = listOf(
        "thank you for watching",
        "thanks for watching",
        "thank you very much for watching",
        "thank you so much for watching",
        "thanks so much for watching",
        "thank you for listening",
        "thanks for listening",
        "please subscribe",
        "like and subscribe",
        "like, comment, and subscribe",
        "don't forget to subscribe",
        "subscribe to my channel",
        "subscribe to our channel",
        "subscribe to the channel",
        "hit the subscribe",
        "click the subscribe",
        "see you in the next video",
        "see you next time",
        "subtitles by",
        "subtitle by",
        "captioned by",
        "captions by",
        "translated by",
        "transcribed by",
        "amara.org",
        "all rights reserved",
        "copyright",
        "mbc news",
        "sbs news",
        "kbs news"
    )

    private val STANDALONE_FILLERS = setOf(
        "uh", "uhh", "uhhh",
        "um", "umm", "ummm",
        "ah", "ahh", "ahhh", "aah", "aaah",
        "eh", "ehh",
        "oh", "ohh", "ohhh", "ooh", "oooh",
        "hm", "hmm", "hmmm", "hmmmm",
        "mm", "mmm", "mmmm", "mhm",
        "ha", "hah", "haha", "huh",
        "uh-huh", "uh huh", "mm-hmm", "mm hmm",
        "erm", "er", "shh", "shhh", "psst", "ugh", "tsk", "phew"
    )

    private val WORD_TOKEN_REGEX = Regex("""[\p{L}\p{N}']+""")

    /**
     * Cleans and validates a raw Whisper segment string.
     *
     * @return Clean dialogue text ready for ML Kit translation, or `null` if the segment should be
     *   dropped as noise, hallucination, filler, or repetition loop.
     */
    fun sanitize(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null

        // Drop lines explicitly flagged with musical note symbols (Whisper singing/BGM hallucination)
        if (MUSIC_SYMBOL_REGEX.containsMatchIn(rawText)) {
            return null
        }

        // Strip bracketed/parenthesized/asterisk sound-effect tags
        var cleaned = rawText
            .replace(BRACKETED_NOISE_REGEX, " ")
            .replace(PAREN_NOISE_REGEX, " ")
            .replace(ASTERISK_NOISE_REGEX, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // Strip leading/trailing stray dashes, bullets, ellipsis noise, or redundant punctuation
        cleaned = cleaned
            .replace(Regex("""^[•·\-–—,:;|\s]+"""), "")
            .replace(Regex("""\s+([,.;:!?])"""), "$1")
            .replace(Regex("""([!?.,])\1{3,}"""), "$1")
            .trim()

        if (cleaned.isEmpty()) return null

        // Must contain at least 2 meaningful alphabetic/ideographic letters (supports Latin, Bengali, CJK, Hindi, Arabic, etc.)
        val letterCount = cleaned.count { Character.isLetter(it) }
        if (letterCount < 2) return null

        val lowerNormalized = cleaned
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}\s'-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (lowerNormalized.isEmpty()) return null

        // Drop standalone filler sounds ("uh", "um", "ah", "hmm", "oh", etc.)
        if (lowerNormalized in STANDALONE_FILLERS) {
            return null
        }

        // Drop known Whisper YouTube/outro/credits hallucination phrases
        if (HALLUCINATION_PHRASES.any { phrase -> lowerNormalized.contains(phrase) }) {
            return null
        }
        // Drop standalone or ultra-short "Subscribe" hallucinations
        if (lowerNormalized == "subscribe" ||
            (lowerNormalized.contains("subscribe") && lowerNormalized.split(' ').size <= 3)
        ) {
            return null
        }
        // Drop URL/domain hallucinations (e.g., "www.mooji.org", "amara.org")
        if (cleaned.contains("www.", ignoreCase = true) ||
            cleaned.contains("http://", ignoreCase = true) ||
            cleaned.contains("https://", ignoreCase = true)
        ) {
            return null
        }

        // Drop repeated-word / repeated-phrase hallucination loops
        val tokens = WORD_TOKEN_REGEX.findAll(lowerNormalized).map { it.value }.toList()
        if (hasRepetitionLoop(tokens)) {
            return null
        }

        return cleaned
    }

    private fun hasRepetitionLoop(tokens: List<String>): Boolean {
        if (tokens.size < 3) return false

        // 1. Same word repeated 3 or more times consecutively (e.g., "you you you", "thank thank thank")
        var runLength = 1
        for (i in 1 until tokens.size) {
            if (tokens[i] == tokens[i - 1]) {
                runLength++
                if (runLength >= 3) return true
            } else {
                runLength = 1
            }
        }

        // 2. 2-word phrase repeated 3+ times consecutively (e.g., "i am i am i am")
        if (tokens.size >= 6) {
            for (i in 0..(tokens.size - 6)) {
                val a = tokens[i]
                val b = tokens[i + 1]
                if (tokens[i + 2] == a && tokens[i + 3] == b &&
                    tokens[i + 4] == a && tokens[i + 5] == b
                ) {
                    return true
                }
            }
        }

        // 3. Single token dominating >= 75% of a 4+ word segment (e.g., "oh, oh, oh, oh!")
        if (tokens.size >= 4) {
            val maxFreq = tokens.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (maxFreq * 4 >= tokens.size * 3) {
                return true
            }
        }

        return false
    }
}
