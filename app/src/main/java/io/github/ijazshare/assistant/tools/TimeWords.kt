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

/**
 * What the words of a time request point at: where the clock time asked for starts, which decides whether
 * a later "in" is the time asked for or part of the task (see [StatedTime.relativeMinutes]), and whether
 * the day asked for is past tomorrow.
 */
internal object TimeWords {

    private val DAY_PARTS = setOf("tonight", "morning", "evening", "afternoon")
    private val DETERMINERS = setOf("my", "the", "a", "an", "our", "his", "her", "their", "your", "that", "this", "its")

    /**
     * The index of the clock time asked for in [words], or -1: "at 9", "8 tonight", "9 o'clock", "3 pm",
     * "noon". Not one inside a noun phrase, which only says which appointment: "my 3 pm appointment in
     * 30 minutes" is in 30 minutes.
     */
    fun clockIndex(words: List<String>): Int = words.indices.firstOrNull { startsClock(words, it) } ?: -1

    /**
     * Whether the transcript asks for a day past tomorrow ("the day after tomorrow"). The alarm and
     * reminder tools only have today and tomorrow, so the model cannot say it either way.
     */
    fun beyondTomorrow(transcript: String): Boolean =
        transcript.lowercase().split(NOT_LETTERS).zipWithNext().any { it == "after" to "tomorrow" }

    private val NOT_LETTERS = Regex("[^a-z]+")

    private fun startsClock(words: List<String>, i: Int): Boolean {
        val word = words[i]
        val next = words.getOrNull(i + 1)
        return when {
            word == "at" -> next != null && (isNumber(next) || next in StatedTime.NOONS || next == "midnight")
            word in StatedTime.NOONS || word == "midnight" -> words.getOrNull(i - 1) !in DETERMINERS
            word in StatedTime.MERIDIEMS || word in DAY_PARTS -> hourBefore(words, i)
            word == "clock" && words.getOrNull(i - 1) == "o" -> hourBefore(words, i - 1)
            else -> false
        }
    }

    /**
     * Whether a number just before [cue] is the hour, and not named in a noun phrase: what comes before
     * the run of numbers says whether it is the time or an appointment's name.
     */
    private fun hourBefore(words: List<String>, cue: Int): Boolean {
        var start = cue
        while (start > 0 && isNumber(words[start - 1])) start--
        return start < cue && words.getOrNull(start - 1) !in DETERMINERS
    }

    private fun isNumber(word: String): Boolean =
        word.first().isDigit() || word in TimeResolver.SPOKEN_NUMBERS || word in StatedTime.TENS
}
