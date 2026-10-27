/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.stt

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
     * [HALF_LIFE_DAYS] days. A star is only a light boost ([STARRED_BONUS], less than one
     * exchange two months old), so it settles a near tie and never puts a contact the user
     * does not reach ahead of one they do; equal scores go to whoever was reached last. A
     * starred contact nobody has reached is named only if every such contact fits in the
     * places left, because a slice of the favourites is an arbitrary list. Numbers
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
            val line = lines.getOrPut(digits.takeLast(KEY_DIGITS)) { Line(exchange.number) }
            line.score += 0.5.pow(ageDays / HALF_LIFE_DAYS)
            line.lastMillis = maxOf(line.lastMillis, exchange.atMillis)
        }

        // Keyed case-insensitively, so two numbers for one person make one name.
        val people = LinkedHashMap<String, Person>()
        fun credit(name: String, score: Double, lastMillis: Long = Long.MIN_VALUE) {
            val spoken = name.trim()
            if (spoken.isEmpty() || spoken.any { it.isDigit() }) return
            val person = people.getOrPut(spoken.lowercase()) { Person(spoken) }
            person.score += score
            person.lastMillis = maxOf(person.lastMillis, lastMillis)
        }

        for (line in lines.values.sortedWith(
            compareByDescending<Line> {
                it.score
            }.thenByDescending { it.lastMillis },
        ).take(limit * LOOKUPS_PER_NAME)) {
            if (people.size >= limit) break
            nameOf(line.number)?.let { credit(it, line.score, line.lastMillis) }
        }
        addStarred(people, starred, limit) { name, score, last -> credit(name, score, last) }

        return people.values
            .sortedWith(
                compareByDescending<Person> { it.score }
                    .thenByDescending { it.lastMillis }
                    .thenBy { it.name },
            )
            .take(limit)
            .map { it.name }
    }

    /** Boosts the starred names in [people], adding those nobody reached only if they all fit in [limit]. */
    private fun addStarred(
        people: MutableMap<String, Person>,
        starred: List<String>,
        limit: Int,
        credit: (String, Double, Long) -> Unit,
    ) {
        val reached = people.size
        val unreached = HashSet<String>()
        for (name in starred) {
            val key = name.trim().lowercase()
            val known = key in people
            credit(name, STARRED_BONUS, Long.MIN_VALUE)
            if (!known && key in people) unreached += key
        }
        // Stars alone cannot choose among more favourites than there are places for.
        if (unreached.size > limit - reached) unreached.forEach { people.remove(it) }
    }

    private class Line(val number: String) {
        var score = 0.0
        var lastMillis = Long.MIN_VALUE
    }

    private class Person(val name: String) {
        var score = 0.0
        var lastMillis = Long.MIN_VALUE
    }

    /** How long it takes an exchange to count half as much. */
    const val HALF_LIFE_DAYS = 30.0

    /** A star adds less than one exchange 60 days old (0.25), so it only settles near ties. */
    const val STARRED_BONUS = 0.1

    /** Lookups allowed per name wanted; the rest of the budget covers numbers that are no contact. */
    const val LOOKUPS_PER_NAME = 5

    /** Fewer digits than a phone line: a short code, which is a service rather than a person. */
    private const val MIN_LINE_DIGITS = 7

    /** Digits that identify a line whether or not the country code was stored with it. */
    private const val KEY_DIGITS = 10

    private const val MILLIS_PER_DAY = 86_400_000.0
}
