/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.assistant

import io.github.ijazshare.assistant.assistant.LocalAnswers.Asked
import java.time.ZonedDateTime
import kotlin.text.RegexOption.IGNORE_CASE

/**
 * The time and date somewhere else, for [LocalAnswers]: the phone's clock, read through
 * [Places].
 *
 * Left to the 4B these were made up: Johannesburg at 09:32 when it was 23:32 there, Sydney
 * at 6:57 AM when it was 8:57 on the next day. So every way of asking that the clock can
 * settle is settled here, the polite ("can you tell me the time in Tokyo") and the terse
 * ("Tokyo time") alike, and so is how far apart two places' clocks are. A place the phone
 * cannot pin to one time is asked about or declined, never handed on to be guessed, and so
 * is the time somewhere when it is noon here, which is not now.
 *
 * What is not a question about a clock somewhere is left alone, so alarms, timers and
 * messages that mention a place still go to the model.
 */
internal object WorldClock {

    /** Said before a question without changing it: "hey", "okay so", "can you tell me", "do you know". */
    private val LEAD = Regex(
        """^(?:\W*\b(?:hey(?:\s+machine)?|hi|ok(?:ay)?|um+|uh+|erm?|so|well|and|alright|please|excuse\s+me|""" +
            """sorry)\b)*\W*(?:(?:can|could|would|will)\s+you\s+(?:please\s+)?|do\s+you\s+know\s+|""" +
            """i(?:\s+want|\s+would\s+like|['’]d\s+like)\s+to\s+know\s+)?""",
        IGNORE_CASE,
    )

    /** Where a place begins: "what time is it in Sydney", "on the West Coast". */
    private val IN = Regex("""\b(?:in|on(?=\s+the\s+(?:east|west)\s+coast\b))\b""", IGNORE_CASE)

    /** "If it's 3 PM here", "when it's noon in New York": a time other than now. */
    private val THEN = Regex("""[\s,]*\b(?:if|when)\s+it(?:['’]s|\s+is)\b[^,?!]*,?""", IGNORE_CASE)

    /** Two places asked about at once: "Tokyo and London", "Tokyo vs London". */
    private val AND = Regex("""\s+(?:and|vs\.?|versus|&)\s+""", IGNORE_CASE)

    private val LETTERS = Regex("""[\p{L}\p{M}]+""")

    /** Two places, or one and here: how far the first's clock is ahead of the other's. */
    private val APART = listOf(
        // "What's the time difference between London and New York?"
        """(?:what(?:['’]s|\s+is)\s+)?(?:the\s+)?time\s+difference\s+between\s+(.+?)\s+and\s+(.+?)""",
        // "How many hours difference is there between New York and Karachi?"
        """how\s+many\s+hours(?:\s+difference)?(?:\s+(?:is|are)\s+there)?\s+between\s+(.+?)\s+and\s+(.+?)""",
        // "What's the time difference with Pakistan?", "... in Tokyo?"
        """(?:what(?:['’]s|\s+is)\s+)?(?:the\s+)?time\s+difference\s+(?:in|with|for|to|from)\s+(.+?)""",
        // "How many hours ahead is London?"
        """how\s+many\s+hours\s+(?:ahead|behind)\s+is\s+(.+?)(?:\s+(?:of|from|than)\s+(.+?))?""",
        // "Is London five hours ahead?"
        """is\s+(.+?)\s+\S+\s+hours?\s+(?:ahead|behind)(?:\s+(?:of|from)\s+(.+?))?""",
    ).map { Regex("""^$it\W*$""", IGNORE_CASE) }

    /** "How far ahead is Sydney": as often a distance as a time, so only about a place the phone knows. */
    private val HOW_FAR =
        Regex("""^how\s+far\s+(?:ahead|behind)\s+is\s+(.+?)(?:\s+(?:of|from|than)\s+(.+?))?\W*$""", IGNORE_CASE)

    /** The phone's own place, in a question about two. */
    private val HERE = setOf("here", "me", "my time", "my location", "where i am")

    private const val NOT_NOW = "I can only tell you the time as it is right now, not work it out for another time."

    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE

    /** The answer, or null if [transcript] does not ask about the clock somewhere else. */
    fun answer(transcript: String, now: ZonedDateTime, places: Places): String? {
        val text = transcript.replaceFirst(LEAD, "")
        // Only declined if what is left is a clock question: "remind me to call when it's
        // nine in London" is a command, and stays the model's.
        THEN.find(text)?.let { clause ->
            val rest = text.removeRange(clause.range)
            return NOT_NOW.takeIf { answer(rest, now, places) != null || LocalAnswers.asked(rest) != null }
        }
        return apart(text, now, places) ?: inPlace(text, now, places) ?: placeTime(text, now, places)
    }

    /** "What time is it in Sydney", "time in Sydney", "In Sydney, what time is it". */
    private fun inPlace(text: String, now: ZonedDateTime, places: Places): String? {
        val at = IN.find(text) ?: return null
        val before = text.substring(0, at.range.first)
        val after = text.substring(at.range.last + 1)
        val (question, place) = if (before.isNotBlank()) before to after else fronted(after) ?: return null
        val asked = LocalAnswers.asked(question, elsewhere = true) ?: return null
        return about(asked, place, at.value.lowercase(), now, places)
    }

    /** "London, what time is it", after the "in": the question, then the place. */
    private fun fronted(after: String): Pair<String, String>? {
        val comma = after.indexOf(',')
        if (comma >= 0) return after.substring(comma + 1) to after.substring(0, comma)
        return LETTERS.findAll(after).drop(1).map { it.range.first }
            .firstOrNull { LocalAnswers.asked(after.substring(it), elsewhere = true) != null }
            ?.let { after.substring(it) to after.substring(0, it) }
    }

    /** "Tokyo time", "What's London time": a place's time, with no "in" to say a place follows. */
    private fun placeTime(text: String, now: ZonedDateTime, places: Places): String? {
        if (IN.containsMatchIn(text)) return null
        val start = LETTERS.findAll(text).firstOrNull { !LocalAnswers.asks(it.value) } ?: return null
        val place = places.timeOf(text.substring(start.range.first), now.toInstant()) ?: return null
        return reply(Asked.CLOCK, place, "in", now)
    }

    /** The time in [place], or in each place it names, or why not. */
    private fun about(asked: Asked, place: String, on: String, now: ZonedDateTime, places: Places): String? {
        val at = now.toInstant()
        val whole = places.find(place, at) ?: return null
        // "Tokyo and London": each one, unless together they are one place, as Trinidad and
        // Tobago is, or one of them is nowhere the phone knows.
        val each = AND.split(place).takeIf { it.size > 1 && whole is Place.Unknown }
            ?.map { places.find(it, at) }
            ?.takeIf { found -> found.none { it == null || it is Place.Unknown } }
        return (each?.filterNotNull() ?: listOf(whole)).joinToString(" ") { reply(asked, it, on, now) }
    }

    /** What to say about [place]: its time or date, or why the phone will not give one. */
    private fun reply(asked: Asked, place: Place, on: String, now: ZonedDateTime): String = when (place) {
        is Place.Known -> {
            val there = now.withZoneSameInstant(place.zone)
            "It is ${LocalAnswers.reading(asked, there, now.toLocalDate())} $on ${place.name}."
        }

        // Asks for a whole question: the session ends once this is said, and a city said on
        // its own next time is no question at all.
        is Place.Several -> "${place.name.replaceFirstChar(Char::uppercaseChar)} has more than one time zone. " +
            "Ask me about a city there."

        is Place.Shared -> "There is more than one ${place.name}. Ask me about a city in the one you mean."

        is Place.Stale -> "My time-zone data for ${place.name} is out of date, so I can't be sure of the time there."

        is Place.Unknown -> "I don't know the time zone for ${place.name}."
    }

    /** "What's the time difference between London and New York", "how far ahead is Sydney". */
    private fun apart(text: String, now: ZonedDateTime, places: Places): String? {
        val far = HOW_FAR.matchEntire(text.trim())
        val match = far ?: APART.firstNotNullOfOrNull { it.matchEntire(text.trim()) } ?: return null
        val here = Place.Known("you", now.zone)
        val side = { said: String -> if (said.trim().lowercase() in HERE) here else places.find(said, now.toInstant()) }
        // "How far ahead is the exit" is about a distance.
        val first = side(match.groupValues[1])?.takeUnless { far != null && it is Place.Unknown } ?: return null
        val second = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { side(it) ?: return null } ?: here
        return if (first == here) gap(second, first, now) else gap(first, second, now)
    }

    /** How far [a]'s clock is ahead of or behind [b]'s right now, or why the phone will not say. */
    private fun gap(a: Place, b: Place, now: ZonedDateTime): String {
        if (a !is Place.Known) return reply(Asked.CLOCK, a, "in", now)
        if (b !is Place.Known) return reply(Asked.CLOCK, b, "in", now)
        val at = now.toInstant()
        val seconds = a.zone.rules.getOffset(at).totalSeconds - b.zone.rules.getOffset(at).totalSeconds
        return when {
            seconds > 0 -> "${a.name} is ${span(seconds)} ahead of ${b.name} right now."
            seconds < 0 -> "${a.name} is ${span(-seconds)} behind ${b.name} right now."
            else -> "${a.name} has the same time as ${b.name} right now."
        }
    }

    /** "4 hours", "5 hours and 30 minutes", "45 minutes". */
    private fun span(seconds: Int): String {
        val hours = seconds / SECONDS_PER_HOUR
        val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val whole = if (hours == 1) "1 hour" else "$hours hours"
        return when {
            minutes == 0 -> whole
            hours == 0 -> "$minutes minutes"
            else -> "$whole and $minutes minutes"
        }
    }
}
