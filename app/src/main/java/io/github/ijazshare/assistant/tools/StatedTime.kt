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

import io.github.ijazshare.assistant.tools.TimeExpressions.Kind
import java.time.LocalDateTime

/**
 * The time a transcript states outright, worked out in code rather than taken from the model.
 *
 * A 1B model turned "in 30 minutes" at 09:04 into 3:00 tomorrow: counting forward from a clock it
 * was told is the same arithmetic it cannot do (see [TimeResolver]). "In N minutes" is now plus N,
 * and the day follows from where that lands. A specific time keeps the model's hour, since it is the
 * one that knows which seven, after [TimeResolver.reconcileHour]; the minutes, noon and midnight
 * are read off the words, which is all they need.
 *
 * Only when the transcript says exactly one time. With two ("at 6 ... in 30 minutes", "at 9 ... call
 * Ali at 5") which one the owner means is a judgement, and every rule written to make it ended up
 * with a counter-example, so code does not make it: the model's numbers stand.
 */
object StatedTime {

    /** A time the words state outright; [tomorrow] is null when they leave the day to the model. */
    data class At(val hour: Int, val minute: Int, val tomorrow: Boolean?)

    private const val NOON = 12
    private const val LAST_MINUTE = 59
    private const val MINUTES_PIVOT_HOUR = 3
    private const val HALF_MINUTE_SECONDS = 30L
    private const val MAX_RELATIVE_MINUTES = 24 * 60

    /**
     * The time [transcript] states, or null when it states none or several, in which case the model's
     * numbers stand. [hour] and [minute] are the model's.
     */
    fun of(transcript: String, hour: Int?, minute: Int?, now: LocalDateTime = LocalDateTime.now()): At? {
        val only = TimeExpressions.of(transcript).singleOrNull() ?: return null
        val words = TimeExpressions.words(transcript)
        val (said, minutes) = when (only.kind) {
            Kind.LENGTH -> return fromNow(only.minutes, now)

            Kind.NOON -> NOON to 0

            Kind.MIDNIGHT -> 0 to 0

            Kind.CLOCK -> {
                // The hour of the one time said, not the first small number: "take 2 pills at 3 p.m." is 3.
                // "p.m." is read here, where it can only belong to that time; the model-led path
                // for several times keeps main's reading, which never saw it.
                val cued = transcript.replace(TimeResolver.DOTTED_MERIDIEM, "$1m")
                val reconciled = TimeResolver.reconcileHour(cued, hour, only.hour) ?: return null
                reconciled to (minuteSaid(transcript, words, reconciled) ?: minute ?: 0)
            }
        }
        // "The day after tomorrow" is two days on, which neither this nor the tools can say: the caller
        // declines it first (see [TimeWords.beyondTomorrow]), and here it is left to the model's day.
        val dayAfter = TimeWords.beyondTomorrow(transcript)
        return At(said, minutes, tomorrow = if ("tomorrow" in words && !dayAfter) true else null)
    }

    /** [minutes] from [now], to the nearest minute (the clock is in minutes, and early is worse than late). */
    private fun fromNow(minutes: Int, now: LocalDateTime): At? {
        if (minutes !in 1..MAX_RELATIVE_MINUTES) return null
        val due = now.plusMinutes(minutes.toLong()).plusSeconds(HALF_MINUTE_SECONDS)
        return At(due.hour, due.minute, tomorrow = due.toLocalDate() != now.toLocalDate())
    }

    /**
     * The minutes of an hour said with its minutes: "9:35", "9.35" and "9 35 am" are 35, "3 pm"
     * is 0. Null when the words do not say, which leaves it to the model: "half past three pm"
     * says its minutes in words, and "3 pm" there is not o'clock.
     */
    private fun minuteSaid(transcript: String, words: List<String>, hour: Int): Int? {
        val minutes = digitMinutes(transcript, hour)
        if (minutes != null) return minutes.takeIf { it <= LAST_MINUTE }
        // The hour itself before the cue, not minutes said after it: "seven ten pm" and "7 5 pm" are not 7 o'clock.
        val oclock = words.indices.any { i ->
            val before = words.getOrNull(i - 1)
            words.getOrNull(i + 1) in TimeExpressions.MERIDIEMS && TimeResolver.clockHour(words[i]) != null &&
                (before == null || (TimeResolver.clockHour(before) == null && before != "oh" && before != "o"))
        }
        val inWords = words.windowed(size = MINUTES_PIVOT_HOUR).any { (minutesWord, pivot, next) ->
            TimeResolver.clockHour(next) != null && TimeResolver.isMinutes(minutesWord) &&
                (pivot in TimeResolver.PAST_THE_HOUR || pivot in TimeResolver.TO_THE_HOUR)
        }
        return if (oclock && !inWords) 0 else null
    }

    /**
     * The minutes of an H:MM, H.MM or "H MM am" that is the hour said, not any number in the sentence:
     * "at 3 pm to pay the 12.50 bill" is 3:00. A bare dot needs the "at" or am/pm that makes it a time
     * and not a price.
     */
    private fun digitMinutes(transcript: String, hour: Int): Int? {
        val text = transcript.lowercase().replace(TimeResolver.DOTTED_MERIDIEM, "$1m")
        val found = CLOCK_DIGITS.findAll(text).firstOrNull { match ->
            val after = text.substring(match.range.last + 1).trimStart()
            match.groupValues[1].toInt() % NOON == hour % NOON && (
                !match.value.contains('.') || text.substring(0, match.range.first).trimEnd().endsWith(" at") ||
                    after.startsWith("am") || after.startsWith("pm")
                )
        }
        // Whichever of the two forms matched has its minutes in the one group that is not empty.
        return found?.groupValues?.drop(2)?.firstNotNullOfOrNull { it.toIntOrNull() }
    }

    // 9:35 and 9.35, or 9 35 with the am or pm that makes it a time and not two numbers.
    private val CLOCK_DIGITS = Regex("""\b(\d{1,2})(?:[:.](\d{2})(?!\d)|\s(\d{2})\s?(?:am|pm)\b)""")
}
