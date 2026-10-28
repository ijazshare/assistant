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

import kotlin.math.roundToInt

/**
 * The time expressions a transcript states: a length ("in 30 minutes"), a clock time ("at 9:35", "3 pm",
 * "at 6", "7 o'clock", "seven ten pm", "half past three") and noon or midnight. [StatedTime] reads a
 * time only when there is exactly one, so what counts as one is all that is found here, and the
 * words are never asked which of two is meant.
 */
internal object TimeExpressions {

    private const val HALF = 0.5
    private const val TEN = 10
    private const val MINUTES_PER_HOUR = 60
    private const val PIVOT_WORDS = 3
    private const val CLOCK_HOURS = 12

    /** What a time expression is: a length from now, a clock time, or one of the two named ones. */
    enum class Kind { LENGTH, CLOCK, NOON, MIDNIGHT }

    /** One time expression; [minutes] is the length of a [Kind.LENGTH], [hour] the 12-hour hour of a [Kind.CLOCK]. */
    data class Expression(val kind: Kind, val minutes: Int = 0, val hour: Int? = null)

    /**
     * The time expressions in [transcript], in order: a length ("in 30 minutes", "a reminder for 30
     * minutes"), a clock time ("at 9:35",
     * "9 35 am", "3 pm", "at 6", "for 6", "7 o'clock", "seven ten pm", "half past three") and noon or
     * midnight ("twelve noon" is one). A bare "9" or "12.50" is none: it needs an at, a for or an am/pm.
     */
    fun of(transcript: String): List<Expression> {
        val words = words(transcript)
        val found = mutableListOf<Expression>()
        var at = 0
        while (at < words.size) {
            val (expression, next) = expressionAt(words, at) ?: (null to at + 1)
            expression?.let(found::add)
            at = next
        }
        return found
    }

    private fun expressionAt(words: List<String>, at: Int): Pair<Expression, Int>? = when {
        words[at] == "in" -> durationAt(words, at + 1)?.let { (minutes, next) ->
            Expression(Kind.LENGTH, minutes.roundToInt()) to next
        }

        // "For 2 hours" is how long, not when, except in the request itself: after "remind",
        // "reminder" or "alarm" and before any "to" that starts the task, "a reminder for 5
        // minutes" is five minutes from now, and "remind me to study for 2 hours" is not.
        words[at] == "for" && durationAt(words, at + 1) != null ->
            durationAt(words, at + 1)?.takeIf { words.take(at).let { it.any(TIMED::contains) && "to" !in it } }
                ?.let { (minutes, next) -> Expression(Kind.LENGTH, minutes.roundToInt()) to next }

        // A clock time, after an at or for or bare: "3 pm", "7 o'clock".
        else -> {
            val bare = words[at] != "at" && words[at] != "for"
            val from = if (bare) at else at + 1
            namedAt(words, from) ?: pivotAt(words, from, bare) ?: hourAt(words, from, bare)
        }
    }

    /** Noon or midnight, "twelve noon" included. */
    private fun namedAt(words: List<String>, at: Int): Pair<Expression, Int>? {
        val word = words.getOrNull(at)
        val twelve = word in TWELVES
        val named = words.getOrNull(if (twelve) at + 1 else at)
        val kind = when {
            named == "midnight" -> Kind.MIDNIGHT
            named in NOONS -> Kind.NOON
            else -> return null
        }
        return Expression(kind) to at + (if (twelve) 2 else 1)
    }

    /** "Ten to six", "half past three", after an at or a for, or bare with its am or pm ("ten to six pm"). */
    private fun pivotAt(words: List<String>, at: Int, bare: Boolean): Pair<Expression, Int>? {
        val (minutes, pivot, hour) = words.drop(at).take(PIVOT_WORDS).takeIf { it.size == PIVOT_WORDS } ?: return null
        val said = TimeResolver.clockHour(hour)
        val timed = TimeResolver.isMinutes(minutes) && said != null &&
            (pivot in TimeResolver.PAST_THE_HOUR || pivot in TimeResolver.TO_THE_HOUR)
        // Bare, "2 to 3" is a range, as in "in 2 to 3 hours" and "2 to 3 apples"; only an am or pm makes it a time.
        val cued = !bare || words.getOrNull(at + PIVOT_WORDS) in MERIDIEMS
        if (!timed || !cued || said == null) return null
        // "Ten to one" is in the hour before one, which is twelve.
        val inHour = when {
            pivot !in TimeResolver.TO_THE_HOUR -> said
            said == 1 -> CLOCK_HOURS
            else -> said - 1
        }
        return Expression(Kind.CLOCK, hour = inHour) to at + PIVOT_WORDS
    }

    /** An hour with the minutes said after it ("9 35", "seven ten", "seven oh five") and its am, pm or o'clock. */
    private fun hourAt(words: List<String>, at: Int, bare: Boolean): Pair<Expression, Int>? {
        val hour = words.getOrNull(at)?.let(TimeResolver::clockHour) ?: return null
        var end = at + 1
        if (words.getOrNull(end) in OHS && numberAt(words, end + 1) != null) end++
        numberAt(words, end)?.takeIf { (value, _) -> value == value.toInt().toDouble() && value < MINUTES_PER_HOUR }
            ?.let { end = it.second }
        val cue = when {
            words.getOrNull(end) in MERIDIEMS -> 1
            words.getOrNull(end) == "o" && words.getOrNull(end + 1) == "clock" -> 2
            else -> 0
        }
        // Without an at or for, a number is only a time when something says so: "3 pm", "7 o'clock".
        return if (cue > 0 || !bare) Expression(Kind.CLOCK, hour = hour) to end + cue else null
    }

    /**
     * A length of time such as "an hour" or "15 minutes", joined by "and" or nothing, starting at [from]:
     * its minutes and the index after it. "In 30 minutes", "in an hour and a half", "in 2 and a half
     * hours", "in 1 hour and 15 minutes", "in 90 min", with digits or words.
     */
    private fun durationAt(words: List<String>, from: Int): Pair<Double, Int>? {
        var total = 0.0
        var at = from
        while (true) {
            val (minutes, next) = termAt(words, at) ?: break
            total += minutes
            at = if (words.getOrNull(next) == "and") next + 1 else next
        }
        return if (total > 0) total to at else null
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
    fun words(transcript: String): List<String> = transcript.lowercase()
        .replace(TimeResolver.DOTTED_MERIDIEM, "$1m")
        .replace('-', ' ')
        .replace(TimeResolver.DIGIT_LETTER_BOUNDARY, " ")
        .split(DECIMAL_BREAK)
        .map { it.trim('.') }
        .filter { it.isNotEmpty() }

    private val DECIMAL_BREAK = Regex("""[^a-z0-9.]+""")
    val MERIDIEMS = setOf("am", "pm")
    private val TIMED = setOf("remind", "reminder", "reminders", "alarm", "alarms")
    private val NOONS = setOf("noon", "midday")
    private val TWELVES = setOf("twelve", "12")
    private val OHS = setOf("oh", "o")
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
