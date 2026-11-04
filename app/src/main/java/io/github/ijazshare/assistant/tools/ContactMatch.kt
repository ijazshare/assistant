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
        val words = name.split(SEP).filter { it.isNotEmpty() }.map(::titled)
        // Every spoken word must be one of the contact's name words: "osman" matches
        // "Osman Khan", "john smith" matches "John Smith", but "me" does not match "MI Aziz".
        // The owner writes Molana as "Ml" and says it or not either way: "Molana Idris" is "Ml Idris
        // Karim" and "Idris Karim"; the title alone names only a contact that has it.
        val queryWords = query.split(SEP).filter { it.isNotEmpty() }.map(::titled)
            .filter { it != TITLE || TITLE in words }
        return queryWords.isNotEmpty() && queryWords.all { it in words }
    }

    /** Whether [word] is the title the owner writes "Ml", in any spelling Whisper or a contact uses. */
    fun isTitle(word: String): Boolean = word.lowercase() in TITLES

    private fun titled(word: String) = if (word in TITLES) TITLE else word

    private const val TITLE = "ml"
    private val TITLES = setOf(TITLE, "molana", "maulana", "moulana", "mowlana", "mawlana", "mulana", "maolana")

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
        // Compared without spaces, punctuation or the title: Whisper writes one heard name as "Be lol",
        // "Be-lol" or "Belol", and "Molana Rahid Mia" is saved as "rahid mia".
        val tag = compact(tagLine(spoken))
        return tag.isNotEmpty() && note.lines().any { compact(tagLine(it)) == tag }
    }

    /** The note line that tags a contact as [spoken]: "my assistant" is "assistant", "Molana Rahid" is "rahid". */
    fun tagLine(spoken: String): String =
        spoken.lowercase().trim().removePrefix("my ").split(SEP).filter { it.isNotEmpty() && it !in TITLES }
            .joinToString(" ")

    private fun compact(text: String) = text.lowercase().filter(Char::isLetterOrDigit)

    /**
     * True if [spoken] sounds like [display], titles aside: word by word ("Rahid Mia" and "Ml Rohed Miah"),
     * or run together, since Whisper splits and joins names ("Be lol" and "Bilal Khan", "Rehidmia" and
     * "Rohed Miah"). Only ever a guess to ask about ("Did you mean ...?"), never one to act on.
     */
    fun soundsAlike(spoken: String, display: String): Boolean {
        val heard = tagLine(spoken).split(' ').filter { it.isNotEmpty() }
        val name = tagLine(display).split(' ').filter { it.isNotEmpty() }
        val joined = soundKey(heard.joinToString(""))
        if (joined.length < MIN_KEY || name.isEmpty()) return false
        val keys = name.map(::soundKey)
        val runTogether = (keys + soundKey(name.joinToString(""))).any { close(joined, it) }
        return runTogether || heard.all { word -> keys.any { close(soundKey(word), it) } }
    }

    private const val MIN_KEY = 3

    /**
     * Names for the user themselves, which must resolve to their own number, not a contact. Read
     * as words, so the model's "Me." is still the owner and never a contact with "me" in its name.
     */
    fun isSelf(spoken: String): Boolean =
        spoken.lowercase().split(SEP).filter { it.isNotEmpty() }.joinToString(" ") in
            setOf("me", "myself", "my number", "my phone", "my cell")
}

/** Levenshtein distance: the fewest letters added, dropped or changed to turn [a] into [b]. */
private fun distance(a: String, b: String): Int {
    var row = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val next = IntArray(b.length + 1)
        next[0] = i + 1
        for (j in b.indices) {
            next[j + 1] = minOf(row[j + 1] + 1, next[j] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
        }
        row = next
    }
    return row[b.length]
}

// ponytail: a crude sound key (vowels alike, h silent, c/k/q, s/z, v/w), tuned on one real mishearing
// ("Rahid Mia" for "Rohed Miah"); Double Metaphone or espeak phonemes if it misses names the owner says.
private fun soundKey(text: String): String {
    val letters = text.lowercase().filter { it in 'a'..'z' }.replace("ph", "f").replace("x", "ks").replace("h", "")
    val mapped = letters.map { if (it in VOWELS) 'a' else ALIKE[it] ?: it }
    return buildString { mapped.forEach { if (isEmpty() || last() != it) append(it) } }
}

/** The same sound, or one letter off for a key longer than [LONG_KEY]. */
private fun close(a: String, b: String): Boolean =
    a.isNotEmpty() && b.isNotEmpty() && distance(a, b) <= if (minOf(a.length, b.length) > LONG_KEY) 1 else 0

private const val LONG_KEY = 5
private const val VOWELS = "aeiouy"
private val ALIKE = mapOf('c' to 'k', 'q' to 'k', 'z' to 's', 'w' to 'v')
