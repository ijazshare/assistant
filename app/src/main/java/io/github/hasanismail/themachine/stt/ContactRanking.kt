/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.stt

import kotlin.math.pow

/** One call or text with [number] at [atMillis], as the call log or the SMS store records it. */
data class Exchange(val number: String, val atMillis: Long)

/**
 * Whose names to bias speech recognition toward: the people the user actually calls and
 * texts, and the contacts they starred.
 *
 * Pure, so it can be tested without a phone. [ContactNames] reads the providers and passes
 * the number-to-name lookup in.
 */
object ContactRanking {

    /**
     * Up to [limit] contact names, best first.
     *
     * Every call or text counts, a recent one more than an old one: its weight halves every
     * [HALF_LIFE_DAYS] days. A starred contact adds as much as one exchange today. Numbers
     * are looked up best first, and at most [LOOKUPS_PER_NAME] per name wanted, because each
     * lookup is a provider query made before the microphone opens. Numbers shorter than a
     * phone line (short codes) are never looked up, and a name with digits in it is a
     * business or a number saved as a contact, not someone to say aloud.
     *
     * Empty when nothing points at anybody. The fallback used to be the first names in the
     * address book, and an unrelated list does harm: on real captures, an unusual name that
     * was not on it came out as English words, or spelt like a commoner name, more often
     * than with no list at all.
     */
    fun rank(
        exchanges: List<Exchange>,
        starred: List<String>,
        nowMillis: Long,
        limit: Int,
        nameOf: (String) -> String?,
    ): List<String> {
        if (limit <= 0) return emptyList()

        // One entry per line. Keyed on the last digits, so "+1 212 555 0101" in the call log
        // and "(212) 555-0101" in the SMS store count together.
        val lines = LinkedHashMap<String, Line>()
        for (exchange in exchanges) {
            val digits = exchange.number.filter { it.isDigit() }
            if (digits.length < MIN_LINE_DIGITS) continue
            val ageDays = (nowMillis - exchange.atMillis).coerceAtLeast(0L).toDouble() / MILLIS_PER_DAY
            lines.getOrPut(digits.takeLast(KEY_DIGITS)) { Line(exchange.number) }.score +=
                0.5.pow(ageDays / HALF_LIFE_DAYS)
        }

        // Keyed case-insensitively, so two numbers for one person make one name.
        val people = LinkedHashMap<String, Person>()
        fun credit(name: String, score: Double) {
            val spoken = name.trim()
            if (spoken.isEmpty() || spoken.any { it.isDigit() }) return
            people.getOrPut(spoken.lowercase()) { Person(spoken) }.score += score
        }

        for (line in lines.values.sortedByDescending { it.score }.take(limit * LOOKUPS_PER_NAME)) {
            if (people.size >= limit) break
            nameOf(line.number)?.let { credit(it, line.score) }
        }
        for (name in starred) credit(name, STARRED_WEIGHT)

        return people.values
            .sortedWith(compareByDescending<Person> { it.score }.thenBy { it.name })
            .take(limit)
            .map { it.name }
    }

    private class Line(val number: String) {
        var score = 0.0
    }

    private class Person(val name: String) {
        var score = 0.0
    }

    /** How long it takes an exchange to count half as much. */
    const val HALF_LIFE_DAYS = 30.0

    /** A star counts as much as one call or text today. */
    const val STARRED_WEIGHT = 1.0

    /** Lookups allowed per name wanted; the rest of the budget covers numbers that are no contact. */
    const val LOOKUPS_PER_NAME = 5

    /** Fewer digits than a phone line: a short code, which is a service rather than a person. */
    private const val MIN_LINE_DIGITS = 7

    /** Digits that identify a line whether or not the country code was stored with it. */
    private const val KEY_DIGITS = 10

    private const val MILLIS_PER_DAY = 86_400_000.0
}
