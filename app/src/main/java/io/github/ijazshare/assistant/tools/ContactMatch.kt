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
 * Whether a spoken name is a genuine match for a contact's display name.
 *
 * The contacts provider's filter is loose — asked for "me" it returned "MI Aziz", and a
 * message went to a father-in-law at 3am. Texting the wrong person is the worst thing this
 * feature can do, so a match must be earned: the spoken name has to be a whole word of the
 * display name (a first name, a last name, a nickname), or the display name itself — not a
 * substring that happened to overlap.
 */
object ContactMatch {

    private val SEP = Regex("[^\\p{L}\\p{N}]+")

    /** True if [spoken] names the contact called [display]. */
    fun matches(spoken: String, display: String): Boolean {
        val query = spoken.lowercase().trim()
        if (query.isEmpty() || display.isBlank()) return false
        val name = display.lowercase().trim()
        if (name == query) return true
        val words = name.split(SEP).filter { it.isNotEmpty() }
        // Every spoken word must be one of the contact's name words: "osman" matches
        // "Osman Khan", "john smith" matches "John Smith", but "me" does not match "MI Aziz".
        val queryWords = query.split(SEP).filter { it.isNotEmpty() }
        return queryWords.isNotEmpty() && queryWords.all { it in words }
    }

    /**
     * True if [number]'s digits were said, in order and together: "text 555 0123", or digit by
     * digit in words. The model writes a number into the recipient whatever was said, and a text
     * or a call to a number nobody said cannot be taken back. A leading 1 it added is allowed.
     */
    fun numberSaid(transcript: String, number: String): Boolean {
        val digits = number.filter(Char::isDigit)
        val wanted = if (digits.length == WITH_COUNTRY_CODE && digits.startsWith('1')) digits.drop(1) else digits
        val heard = transcript.lowercase().split(SEP).joinToString("") { DIGIT_WORDS[it] ?: it.filter(Char::isDigit) }
        return wanted.isNotEmpty() && wanted in heard
    }

    private const val WITH_COUNTRY_CODE = 11
    private val DIGIT_WORDS = mapOf(
        "zero" to "0", "oh" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
    )

    /**
     * True if a contact's [note] tags it as what was [spoken]: a line of the note that is exactly
     * the word, "Assistant" for "text my assistant". A whole line only, never a word inside a
     * sentence: a note mentioning "Sam's dad" must not make "text Sam" his father.
     */
    fun taggedAs(note: String, spoken: String): Boolean {
        val tag = spoken.trim().lowercase().removePrefix("my ").trim()
        return tag.isNotEmpty() && note.lines().any { it.trim().lowercase() == tag }
    }

    /**
     * Names for the user themselves, which must resolve to their own number, not a contact. Read
     * as words, so the model's "Me." is still the owner and never a contact with "me" in its name.
     */
    fun isSelf(spoken: String): Boolean =
        spoken.lowercase().split(SEP).filter { it.isNotEmpty() }.joinToString(" ") in
            setOf("me", "myself", "my number", "my phone", "my cell")
}
