/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tools

/**
 * Turns the pieces of a time the model heard into the numbers Android wants.
 *
 * The division of labour here is deliberate and was arrived at the hard way. Asked for
 * a timer's total length, a 1B model would answer "ten minute timer" with 180 — the
 * number from the nearest worked example — and asked for a 24-hour clock value it read
 * "half past six in the evening" as hour 2. Neither is a prompting problem: small models
 * copy surface patterns and do not reliably do arithmetic.
 *
 * So the model is asked only to report what it actually heard — ten, minutes, evening —
 * and every calculation happens here, where it is ordinary code that can be tested.
 */
object TimeResolver {

    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
    private const val HOURS_PER_DAY = 24L
    private const val NOON = 12
    private const val LAST_HOUR = 23
    private const val LAST_MINUTE = 59

    /** Longest timer worth honouring; beyond a day it is a reminder, not a countdown. */
    private const val MAX_TIMER_SECONDS =
        HOURS_PER_DAY * MINUTES_PER_HOUR * SECONDS_PER_MINUTE

    /**
     * Total seconds for a spoken duration, or null if nothing usable was heard.
     *
     * Absent parts count as zero rather than failing, because "an hour and a half" only
     * ever arrives as some of these fields.
     */
    fun totalSeconds(hours: Int?, minutes: Int?, seconds: Int?): Int? {
        if (hours == null && minutes == null && seconds == null) return null
        val total = (hours ?: 0).toLong() * MINUTES_PER_HOUR * SECONDS_PER_MINUTE +
            (minutes ?: 0).toLong() * SECONDS_PER_MINUTE +
            (seconds ?: 0).toLong()
        if (total <= 0 || total > MAX_TIMER_SECONDS) return null
        return total.toInt()
    }

    /**
     * Validates an hour on a 24-hour clock.
     *
     * The grammar already refuses anything outside 0..23, so this is a second line
     * rather than the first: it also covers a hand-written call and a future model whose
     * output is not grammar-constrained.
     */
    fun hourOf(hour: Int?): Int? = hour?.takeIf { it in 0..LAST_HOUR }

    /**
     * Corrects the model's hour against the hour that was actually said.
     *
     * A 1B model converts to 24-hour time by pattern rather than by arithmetic, and gets
     * it wrong often enough to matter: "half past six in the evening" came back as 16.
     * The words are not ambiguous — six, and evening — and that sum is one line of
     * Kotlin, so it is done here rather than hoped for.
     *
     * Only an explicit part-of-day cue triggers this. Without one the model's answer
     * stands, because "alarm at seven" genuinely does not say which seven.
     */
    fun reconcileHour(transcript: String, hour: Int?): Int? {
        val resolved = hourOf(hour) ?: return hour
        // "6pm" is one token to a plain split, and neither the number six nor the word
        // pm; the boundary between a digit and a letter has to be made explicit first.
        val words = transcript.lowercase()
            .replace(DIGIT_LETTER_BOUNDARY, " ")
            .split(WORD_BREAK)
            .filter { it.isNotEmpty() }
        val evening = words.any { it in EVENING_CUES }
        val morning = words.any { it in MORNING_CUES }
        if (evening == morning) return resolved

        // The first clock-sized number is the hour; a later one is its minutes.
        val said = words.firstNotNullOfOrNull { word ->
            (SPOKEN_NUMBERS[word] ?: word.takeWhile { it.isDigit() }.toIntOrNull())
                ?.takeIf { it in 1..NOON }
        } ?: return resolved

        val corrected = when {
            evening && said == NOON -> NOON
            evening -> said + NOON
            said == NOON -> 0
            else -> said
        }
        return corrected
    }

    private val WORD_BREAK = Regex("""[^a-z0-9]+""")
    private val DIGIT_LETTER_BOUNDARY = Regex("""(?<=\d)(?=[a-z])""")
    private val EVENING_CUES = setOf("pm", "evening", "night", "tonight", "afternoon")
    private val MORNING_CUES = setOf("am", "morning")
    private val SPOKEN_NUMBERS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "noon" to 12, "midday" to 12,
    )

    /** Minutes, defaulting to o'clock, and rejecting anything outside the hour. */
    fun minuteOf(minute: Int?): Int? {
        val value = minute ?: 0
        return if (value in 0..LAST_MINUTE) value else null
    }
}
