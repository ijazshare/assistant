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
 * The time and date somewhere else are the same fact: the same clock, read through the
 * platform's time-zone data, and just as exact. Left to the 4B they were made up —
 * Johannesburg at 09:32 when it was 23:32 there, Sydney at 6:57 AM when it was 8:57 on
 * the next day. A place the phone cannot pin to one time is asked about or declined here,
 * never handed on to be guessed; [Places] decides which.
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

    private val CLOCK_WORDS = setOf("time", "clock", "oclock")
    private val DATE_WORDS = setOf("date", "day", "today", "month", "year")
    private val ASKING = setOf(
        "what", "whats", "which", "tell", "say", "is", "it", "the", "and", "me", "now", "s",
    )

    /** Asked of somewhere else, and only there: "what's the local time in Tokyo". */
    private val ELSEWHERE = ASKING + setOf("current", "currently", "local", "right")

    /** Where a place begins: "what time is it in Sydney". */
    private val IN = Regex("""\bin\b""", RegexOption.IGNORE_CASE)

    private enum class Asked { CLOCK, DATE, BOTH }

    /**
     * The answer, or null if this is not a question the clock can settle.
     *
     * [places] is where a place named after "in" is looked up; tests pass their own,
     * because the phone's comes from android.icu.
     */
    fun of(transcript: String, now: ZonedDateTime = ZonedDateTime.now(), places: Places = Places.device): String? {
        val at = IN.find(transcript)
            ?: return asked(transcript, ASKING)?.let { "It is ${reading(it, now, now.toLocalDate())}." }
        // Only the words before the place have to be a question: "Sydney" is no asking
        // word, but "what time is it in" is nothing else.
        val asked = asked(transcript.substring(0, at.range.first), ELSEWHERE) ?: return null
        val place = places.find(transcript.substring(at.range.last + 1), now.toInstant()) ?: return null
        return when (place) {
            is Place.Known -> {
                val there = now.withZoneSameInstant(place.zone)
                "It is ${reading(asked, there, now.toLocalDate())} in ${place.name}."
            }

            is Place.Several -> {
                val name = place.name.replaceFirstChar(Char::uppercaseChar)
                "$name has more than one time zone. Which city?"
            }

            is Place.Unknown -> "I don't know the time zone for ${place.name}."
        }
    }

    /** What [question] asks about the clock, or null if it is not only that. */
    private fun asked(question: String, vocabulary: Set<String>): Asked? {
        // Split on anything that is not a letter, so "what's" becomes "what" and "s"
        // rather than a word that matches nothing.
        val words = question.lowercase().split(Regex("""[^a-z]+""")).filter { it.isNotEmpty() }
        // A question, not a command: "set a timer" mentions no clock word, and "what
        // time is it" is nothing but asking words and a clock word.
        if (words.none { it in ASKING }) return null
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

    /** The time or the date at [time], or both; with the day too if it is not [today]. */
    private fun reading(asked: Asked, time: ZonedDateTime, today: LocalDate): String {
        val clock = time.format(TIME)
        return when (asked) {
            Asked.BOTH -> "$clock on ${time.format(DATE)}"

            Asked.DATE -> time.format(DATE)

            // "8:57 AM" alone is the wrong morning when Sydney is already on Tuesday.
            Asked.CLOCK -> if (time.toLocalDate() == today) clock else "$clock on ${time.format(DAY)}"
        }
    }
}
