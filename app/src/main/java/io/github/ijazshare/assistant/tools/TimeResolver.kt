/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.tools

import java.time.LocalTime

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
    private const val PADDING = 3

    /**
     * Whether [hour]:[minute] is the current clock minute.
     *
     * The routing prompt states the current time, and when no time is actually spoken the
     * model copies it — so "create a note about this" came back as an alarm for this very
     * minute. That is never a real request: an alarm or reminder for now is useless. The
     * caller treats a match as "no time given" rather than setting something that fires at
     * once (or, once rolled forward, a day later).
     */
    fun echoesNow(hour: Int?, minute: Int?, now: LocalTime = LocalTime.now()): Boolean =
        hour != null && minute != null && hour == now.hour && minute == now.minute

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
            .replace(DOTTED_MERIDIEM, "$1m")
            .replace(DIGIT_LETTER_BOUNDARY, " ")
            .split(WORD_BREAK)
            .filter { it.isNotEmpty() }
        val evening = words.any { it in EVENING_CUES }
        val morning = words.any { it in MORNING_CUES }
        if (evening == morning) return if (evening) resolved else hourAfterAt(words, resolved)

        val said = hourSaid(words) ?: return resolved

        val corrected = when {
            evening && said == NOON -> NOON
            evening -> said + NOON
            said == NOON -> 0
            else -> said
        }
        return corrected
    }

    /**
     * The hour that was said, on a 12-hour clock, or null if no clock-sized number was.
     *
     * Usually the first clock-sized number is the hour and a later one is its minutes.
     * "Ten to six" says its minutes first, though, and read that way it becomes an alarm
     * for 10:50 when 5:50 was asked for. So minutes, then to or past, then a clock-sized
     * number is read as a time, with "to" counting back into the hour before. Both ends
     * are required: the minutes in front keep "set the alarm to seven" and "remind me to
     * call" out of it, and the hour straight after keeps the "one to" of "give one to Ali
     * at ten to six" out of it.
     */
    private fun hourSaid(words: List<String>): Int? {
        val counted = words.windowed(size = 3).firstNotNullOfOrNull { (minutes, pivot, next) ->
            val hour = clockHour(next)
            when {
                hour == null || !isMinutes(minutes) -> null
                pivot in PAST_THE_HOUR -> hour
                pivot in TO_THE_HOUR -> if (hour == 1) NOON else hour - 1
                else -> null
            }
        }
        // Otherwise the first clock-sized number is the hour; a later one is its minutes.
        return counted ?: words.firstNotNullOfOrNull(::clockHour)
    }

    /**
     * With no part-of-day cue the model's hour stands, since "alarm at seven" does not say which
     * seven — unless it is not a seven at all. "Tomorrow at 7" came back as 3, and 3 is neither
     * of the sevens, so the hour said wins. Only the number straight after "at" counts, and not
     * when it is the minutes of "at ten to six". With two "at N" the model's hour stands, unless
     * one is the time asked for: "at 6 o'clock", or right after remind, alarm or me.
     */
    private fun hourAfterAt(words: List<String>, resolved: Int): Int {
        // Padded so the last "at" still has a window of four.
        val padded = words + List(PADDING) { "" }
        val ats = words.indices.filter { i ->
            val (at, hour, pivot) = padded.subList(i, i + PADDING)
            val minutesFirst = isMinutes(hour) && (pivot in PAST_THE_HOUR || pivot in TO_THE_HOUR) &&
                clockHour(padded[i + PADDING]) != null
            at == "at" && !minutesFirst && clockHour(hour) != null
        }
        // Two of them and one is not a time: "look at 2 flats tomorrow at 6". Which is not said.
        val asked = ats.firstOrNull { i ->
            (padded[i + 2] == "o" && padded[i + PADDING] == "clock") || padded.getOrNull(i - 1) in ASKING_WORDS
        }
        val said = (asked ?: ats.singleOrNull())?.let { clockHour(padded[it + 1]) }
        return if (said == null || said % NOON == resolved % NOON) resolved else said
    }

    internal fun clockHour(word: String): Int? =
        (SPOKEN_NUMBERS[word] ?: word.takeWhile { it.isDigit() }.toIntOrNull())
            ?.takeIf { it in 1..NOON }

    internal fun isMinutes(word: String): Boolean =
        word in SPOKEN_MINUTES || word.toIntOrNull() in 1..LAST_MINUTE

    private val WORD_BREAK = Regex("""[^a-z0-9]+""")
    internal val DIGIT_LETTER_BOUNDARY = Regex("""(?<=\d)(?=[a-z])""")

    // "3 p.m." splits into 3, p and m, and then carries no cue at all.
    internal val DOTTED_MERIDIEM = Regex("""\b([ap])\.\s?m\b\.?""")

    private val ASKING_WORDS = setOf("remind", "alarm", "me")
    private val EVENING_CUES = setOf("pm", "evening", "night", "tonight", "afternoon")
    private val MORNING_CUES = setOf("am", "morning")
    internal val SPOKEN_NUMBERS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
        "noon" to 12, "midday" to 12,
    )

    // "Ten past six" is six o'clock and ten; "ten to six" is five o'clock and fifty.
    internal val PAST_THE_HOUR = setOf("past", "after")
    internal val TO_THE_HOUR = setOf("to", "till", "til")

    // The minutes as spoken in front of to or past. "Twenty-five" is already twenty and five
    // by the time this is asked, so the tens are enough. Noon is a time, never an amount.
    private val SPOKEN_MINUTES = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        "twenty", "thirty", "forty", "fifty", "quarter", "half",
    )

    /** Minutes, defaulting to o'clock, and rejecting anything outside the hour. */
    fun minuteOf(minute: Int?): Int? {
        val value = minute ?: 0
        return if (value in 0..LAST_MINUTE) value else null
    }
}
