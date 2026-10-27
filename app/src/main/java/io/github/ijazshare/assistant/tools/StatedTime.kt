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

import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * The time a transcript states outright, worked out in code rather than taken from the model.
 *
 * A 1B model turned "in 30 minutes" at 09:04 into 3:00 tomorrow: counting forward from a clock it
 * was told is the same arithmetic it cannot do (see [TimeResolver]). "In N minutes" is now plus N,
 * and the day follows from where that lands. A specific time keeps the model's hour, since it is the
 * one that knows which seven, after [TimeResolver.reconcileHour]; the minutes, noon and midnight
 * are read off the words, which is all they need.
 */
object StatedTime {

    /** A time the words state outright; [tomorrow] is null when they leave the day to the model. */
    data class At(val hour: Int, val minute: Int, val tomorrow: Boolean?)

    private const val NOON = 12
    private const val LAST_MINUTE = 59
    private const val TEN = 10
    private const val MINUTES_PIVOT_HOUR = 3
    private const val HALF = 0.5
    private const val HALF_MINUTE_SECONDS = 30L
    private const val MAX_RELATIVE_MINUTES = 24 * 60

    /**
     * The time [transcript] states, or null when it states none, in which case the model's numbers
     * stand. [hour] and [minute] are the model's.
     */
    fun of(transcript: String, hour: Int?, minute: Int?, now: LocalDateTime = LocalDateTime.now()): At? {
        relativeMinutes(transcript)?.let { minutes ->
            // To the nearest minute: the clock below this is minutes, and early is worse than late.
            val due = now.plusMinutes(minutes.toLong()).plusSeconds(HALF_MINUTE_SECONDS)
            return At(due.hour, due.minute, tomorrow = due.toLocalDate() != now.toLocalDate())
        }
        val words = clockWords(transcript)
        val (said, minutes) = when {
            words.any { it == "noon" || it == "midday" } -> NOON to 0

            "midnight" in words -> 0 to 0

            else -> {
                val reconciled = TimeResolver.reconcileHour(transcript, hour) ?: return null
                reconciled to (minuteSaid(transcript, words) ?: minute ?: 0)
            }
        }
        return At(said, minutes, tomorrow = if ("tomorrow" in words) true else null)
    }

    /**
     * The minutes of an hour said with its minutes: "9:35", "9.35" and "9 35 am" are 35, "3 pm"
     * is 0. Null when the words do not say, which leaves it to the model: "half past three pm"
     * says its minutes in words, and "3 pm" there is not o'clock.
     */
    private fun minuteSaid(transcript: String, words: List<String>): Int? {
        val digits = CLOCK_DIGITS.find(transcript.lowercase().replace(TimeResolver.DOTTED_MERIDIEM, "$1m"))
        // Whichever of the two forms matched has its minutes in the one group that is not empty.
        val minutes = digits?.groupValues?.drop(2)?.firstNotNullOfOrNull { it.toIntOrNull() }
        if (minutes != null) return minutes.takeIf { it <= LAST_MINUTE }
        val oclock = words.zipWithNext().any { (hour, cue) -> TimeResolver.clockHour(hour) != null && cue in MERIDIEMS }
        val inWords = words.windowed(size = MINUTES_PIVOT_HOUR).any { (minutesWord, pivot, hour) ->
            TimeResolver.clockHour(hour) != null && TimeResolver.isMinutes(minutesWord) &&
                (pivot in TimeResolver.PAST_THE_HOUR || pivot in TimeResolver.TO_THE_HOUR)
        }
        return if (oclock && !inWords) 0 else null
    }

    /**
     * The minutes from now that "in ..." asks for, or null when the transcript names no length of
     * time, or one over a day. "In 30 minutes", "in an hour and a half", "in 2 and a half hours",
     * "in 1 hour and 15 minutes", "in 90 min", with digits or words.
     */
    fun relativeMinutes(transcript: String): Int? {
        val words = clockWords(transcript)
        val minutes = words.indices.filter { words[it] == "in" }
            .firstNotNullOfOrNull { durationAt(words, it + 1) } ?: return null
        return minutes.roundToInt().takeIf { it in 1..MAX_RELATIVE_MINUTES }
    }

    /** Terms such as "an hour" or "15 minutes", joined by "and" or nothing, starting at [from]. */
    private fun durationAt(words: List<String>, from: Int): Double? {
        var total = 0.0
        var at = from
        while (true) {
            val (minutes, next) = termAt(words, at) ?: break
            total += minutes
            at = if (words.getOrNull(next) == "and") next + 1 else next
        }
        return total.takeIf { it > 0 }
    }

    /** One length and the index after it: "an hour", "half an hour", "2 and a half hours", "15 min". */
    private fun termAt(words: List<String>, from: Int): Pair<Double, Int>? {
        var at = from
        var amount: Double
        when (words.getOrNull(at)) {
            "a", "an" -> {
                amount = 1.0
                at++
            }

            "half" -> {
                amount = HALF
                at++
                if (words.getOrNull(at) in ARTICLES) at++
            }

            else -> {
                val (value, next) = numberAt(words, at) ?: return null
                amount = value
                at = next
            }
        }
        // "2 and a half hours" has its half before the unit, "an hour and a half" after it.
        halfAt(words, at)?.let {
            at += it
            amount += HALF
        }
        val unit = DURATION_UNITS[words.getOrNull(at) ?: return null] ?: return null
        at++
        halfAt(words, at)?.let {
            at += it
            amount += HALF
        }
        return amount * unit to at
    }

    /** How many words an "and a half" or "and half" takes at [at], or null if it is not there. */
    private fun halfAt(words: List<String>, at: Int): Int? =
        HALVES.firstOrNull { words.drop(at).take(it.size) == it }?.size

    private fun numberAt(words: List<String>, at: Int): Pair<Double, Int>? {
        val word = words.getOrNull(at) ?: return null
        if (word.first().isDigit()) return word.toDoubleOrNull()?.let { it to at + 1 }
        val tens = TENS[word]
        if (tens != null) {
            // "Twenty five" arrives as two words.
            val ones = words.getOrNull(at + 1)?.let { TimeResolver.SPOKEN_NUMBERS[it] }?.takeIf { it < TEN }
            return if (ones != null) (tens + ones).toDouble() to at + 2 else tens.toDouble() to at + 1
        }
        // Noon is a time, never an amount.
        return TimeResolver.SPOKEN_NUMBERS[word]?.takeIf { word != "noon" && word != "midday" }
            ?.let { it.toDouble() to at + 1 }
    }

    /**
     * The transcript as lowercase words, with "p.m." as pm and "twenty-five" as two words. Decimal
     * points stay, so "1.5" is one word.
     */
    private fun clockWords(transcript: String): List<String> = transcript.lowercase()
        .replace(TimeResolver.DOTTED_MERIDIEM, "$1m")
        .replace('-', ' ')
        .replace(TimeResolver.DIGIT_LETTER_BOUNDARY, " ")
        .split(DECIMAL_BREAK)
        .map { it.trim('.') }
        .filter { it.isNotEmpty() }

    private val DECIMAL_BREAK = Regex("""[^a-z0-9.]+""")

    // 9:35 and 9.35, or 9 35 with the am or pm that makes it a time and not two numbers.
    private val CLOCK_DIGITS = Regex("""\b(\d{1,2})(?:[:.](\d{2})(?!\d)|\s(\d{2})\s?(?:am|pm)\b)""")
    private val MERIDIEMS = setOf("am", "pm")
    private val ARTICLES = setOf("a", "an")
    private val HALVES = listOf(listOf("and", "half"), listOf("and", "a", "half"), listOf("and", "an", "half"))

    private val DURATION_UNITS = mapOf(
        "minute" to 1.0,
        "minutes" to 1.0,
        "min" to 1.0,
        "mins" to 1.0,
        "hour" to 60.0,
        "hours" to 60.0,
        "hr" to 60.0,
        "hrs" to 60.0,
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
}
