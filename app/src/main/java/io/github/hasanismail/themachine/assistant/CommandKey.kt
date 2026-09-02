/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.assistant

/**
 * Reduces a transcript to the form two utterances of the same command share.
 *
 * Whisper writes "Set a timer for 10 minutes." and a person types "ten minute timer";
 * a cache keyed on raw text would treat those as strangers. Case, punctuation, number
 * words, unit spellings and politeness are all removed here, and what remains is the
 * command. Nothing about the meaning is decided at this layer — only what is the same.
 */
object CommandKey {

    /**
     * Words that make a command depend on when it is said.
     *
     * "Remind me in an hour" resolves to a different clock hour every time, so caching
     * its first resolution would fire tomorrow's reminder at today's time. A phrase
     * containing any of these is never cached, whatever tool it resolved to. "Tomorrow"
     * is deliberately absent: it reaches the tool as a flag, not a computed hour, and
     * means the same thing on any day.
     */
    val RELATIVE_TIME_WORDS: Set<String> = setOf(
        "in", "later", "now", "today", "tonight", "this", "next", "after", "before", "from",
        "morning", "afternoon", "evening", "soon", "shortly", "every", "yesterday",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december", "weekend",
    )

    /** Every run of digits in a key, for checking that a number the model emitted was said. */
    fun numbersIn(key: String): Set<String> = DIGITS.findAll(key).map { it.value }.toSet()

    private val DIGITS = Regex("""\d+""")

    private val UNITS = mapOf(
        "min" to "minutes", "mins" to "minutes", "minute" to "minutes",
        "sec" to "seconds", "secs" to "seconds", "second" to "seconds",
        "hr" to "hours", "hrs" to "hours", "hour" to "hours",
        "o'clock" to "oclock",
    )

    private val FILLERS = setOf(
        "please", "hey", "ok", "okay", "thanks", "thank", "you", "could", "can", "would",
        "will", "just", "kindly", "me", "for", "a", "an", "the", "to", "up", "set", "start",
        "make", "create", "put", "on", "go", "ahead", "and",
    )

    private val SMALL = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20,
        "thirty" to 30,
        "forty" to 40,
        "fifty" to 50,
        "sixty" to 60,
        "seventy" to 70,
        "eighty" to 80,
        "ninety" to 90,
    )

    // Keep the colon inside "6:30" and the apostrophe in "o'clock"; drop everything else
    // that is not a letter or a digit.
    private val LONE_COLON = Regex("""(?<!\d):|:(?!\d)""")
    private val NOISE = Regex("""[^\p{L}\p{N}:' ]""")
    private val SPACES = Regex("""\s+""")
    private val WORD_BREAK = Regex("""[^a-z']+""")

    /** "twenty" joins with "one" through "nine", never with "ten" or above. */
    private const val LAST_UNIT_DIGIT = 9

    /** The normalised key, or null if the phrase is too short to mean anything. */
    fun of(transcript: String): String? {
        val words = transcript.lowercase()
            .replace(LONE_COLON, " ")
            .replace(NOISE, " ")
            .replace(SPACES, " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }
        val cleaned = joinNumbers(words)
            .map { UNITS[it] ?: it }
            .filterNot { it in FILLERS }
        return cleaned.joinToString(" ").ifBlank { null }
    }

    /** True if the phrase depends on the time it is said, and so must not be cached. */
    fun isTimeRelative(transcript: String): Boolean =
        transcript.lowercase().split(WORD_BREAK).any { it in RELATIVE_TIME_WORDS }

    /**
     * Rewrites number words as digits, including "twenty five" as one number.
     *
     * "a" before a unit is left to the filler list rather than turned into 1: "a timer"
     * and "a minute" would both become "1", and only one of them means it.
     */
    private fun joinNumbers(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        var index = 0
        while (index < words.size) {
            val word = words[index]
            val tens = TENS[word]
            val unit = words.getOrNull(index + 1)?.let { SMALL[it] }
            when {
                tens != null && unit != null && unit in 1..LAST_UNIT_DIGIT -> {
                    out += (tens + unit).toString()
                    index += 2
                    continue
                }

                tens != null -> out += tens.toString()

                SMALL.containsKey(word) -> out += SMALL.getValue(word).toString()

                else -> out += word
            }
            index++
        }
        return out
    }
}
