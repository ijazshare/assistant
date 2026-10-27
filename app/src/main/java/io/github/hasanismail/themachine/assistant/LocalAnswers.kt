/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.assistant

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Questions the phone can answer exactly, without asking a language model.
 *
 * Deliberately a very short list. The time and the date are things the device knows to
 * the second and a model only knows because they were written into its prompt — and the
 * 1B, asked what time it was, answered by reading out the notification shade. Answering
 * these from the clock is both instant and incapable of being wrong, which no amount of
 * prompting can promise.
 *
 * The time and date somewhere else are the same clock, read through the platform's
 * time-zone data, and [WorldClock] answers them. That data is only as current as the
 * phone's last update, so a place whose clocks have changed since is declined, as is a
 * place the phone cannot pin to one time: neither is ever handed on to be guessed.
 *
 * Everything else goes to the model. This is not a general rules engine and must not
 * grow into one.
 */
object LocalAnswers {

    // In English whatever the phone's language, because the voice reading them out is
    // English: in Spanish "h:mm a" would come out as "2:05 p. m.".
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("EEEE", Locale.US)

    private val CLOCK_WORDS = setOf("time", "clock", "oclock", "hour")
    private val DATE_WORDS = setOf("date", "day", "today", "month", "year")
    private val ASKING = setOf(
        "what", "whats", "which", "tell", "say", "is", "it", "the", "and", "me", "now", "s",
    )

    /**
     * Asked of somewhere else, and only there: "what's the local time over in Tokyo",
     * "what time would it be there", "what day of the week is it in Tokyo".
     */
    private val ELSEWHERE = ASKING + setOf(
        "current", "currently", "local", "right", "exact", "exactly", "over", "there", "at", "moment", "like",
        "do", "they", "have", "would", "be", "of", "week",
    )

    internal enum class Asked { CLOCK, DATE, BOTH }

    /**
     * The answer, or null if this is not a question the clock can settle.
     *
     * [places] is where a place named in the question is looked up; tests pass their own,
     * because the phone's comes from android.icu.
     */
    fun of(transcript: String, now: ZonedDateTime = ZonedDateTime.now(), places: Places = Places.device): String? =
        WorldClock.answer(transcript, now, places)
            ?: asked(transcript)?.let { "It is ${reading(it, now, now.toLocalDate())}." }

    /**
     * What [question] asks about the clock, or null if it is not only that. [elsewhere] is
     * for the words before a place, which may say more, and more tersely: "time in Tokyo".
     */
    internal fun asked(question: String, elsewhere: Boolean = false): Asked? {
        // Split on anything that is not a letter, so "what's" becomes "what" and "s"
        // rather than a word that matches nothing.
        val words = question.lowercase().split(Regex("""[^a-z]+""")).filter { it.isNotEmpty() }
        // A question, not a command: "set a timer" mentions no clock word, and "what
        // time is it" is nothing but asking words and a clock word.
        val asking = words.any { it in ASKING } || (elsewhere && words.any { it == "time" || it == "date" })
        if (!asking) return null
        val vocabulary = if (elsewhere) ELSEWHERE else ASKING
        if (words.any { it !in vocabulary && it !in CLOCK_WORDS && it !in DATE_WORDS }) return null

        val wantsClock = words.any { it in CLOCK_WORDS }
        val wantsDate = words.any { it in DATE_WORDS }
        return when {
            wantsClock && wantsDate -> Asked.BOTH
            wantsClock -> Asked.CLOCK
            wantsDate -> Asked.DATE
            else -> null
        }
    }

    /** Whether [word] only asks, and so cannot begin a place: "what's", "the", "current". */
    internal fun asks(word: String) = word.lowercase() in ELSEWHERE

    /** The time or the date at [time], or both; with the day too if it is not [today]. */
    internal fun reading(asked: Asked, time: ZonedDateTime, today: LocalDate): String {
        val clock = time.format(TIME)
        return when (asked) {
            Asked.BOTH -> "$clock on ${time.format(DATE)}"

            Asked.DATE -> time.format(DATE)

            // "8:57 AM" alone is the wrong morning when Sydney is already on Tuesday.
            Asked.CLOCK -> if (time.toLocalDate() == today) clock else "$clock on ${time.format(DAY)}"
        }
    }
}
