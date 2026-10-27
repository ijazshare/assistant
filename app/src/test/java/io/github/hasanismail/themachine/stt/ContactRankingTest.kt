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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContactRankingTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    // Fictional people on 555-01xx numbers.
    private val book = mapOf(
        "+12125550101" to "Ines Calder",
        "+12125550102" to "Tomas Reyes",
        "+12125550103" to "Priya Nandan",
        "+12125550104" to "Jonah Pike",
        "+12125550105" to "Lena Ortiz",
        "+12125550106" to "Corner Shop 24",
    )

    private fun nameOf(number: String): String? =
        book["+1" + number.filter { it.isDigit() }.takeLast(10)]

    private fun reached(number: String, daysAgo: Double) = Exchange(number, now - (daysAgo * day).toLong())

    private fun rank(exchanges: List<Exchange>, starred: List<String> = emptyList(), limit: Int = 8) =
        ContactRanking.rank(exchanges, starred, now, limit, ::nameOf)

    @Test
    fun putsThePeopleReachedMostFirst() {
        val names = rank(
            listOf(
                reached("+12125550101", 1.0),
                reached("+12125550102", 2.0),
                reached("+12125550102", 3.0),
                reached("+12125550102", 4.0),
                reached("+12125550103", 5.0),
                reached("+12125550103", 6.0),
            ),
        )
        assertThat(names).containsExactly("Tomas Reyes", "Priya Nandan", "Ines Calder").inOrder()
    }

    @Test
    fun aRecentExchangeOutweighsAnOldOne() {
        val names = rank(listOf(reached("+12125550101", 80.0), reached("+12125550102", 1.0)))
        assertThat(names).containsExactly("Tomas Reyes", "Ines Calder").inOrder()
    }

    @Test
    fun countsOneLineTogetherHoweverTheNumberWasWritten() {
        // Three exchanges with one person, stored three ways, beat two with another.
        val names = rank(
            listOf(
                reached("+1 212-555-0101", 10.0),
                reached("(212) 555-0101", 10.0),
                reached("2125550101", 10.0),
                reached("+12125550102", 9.0),
                reached("+12125550102", 9.0),
            ),
        )
        assertThat(names).containsExactly("Ines Calder", "Tomas Reyes").inOrder()
    }

    @Test
    fun namesAPersonWithTwoNumbersOnce() {
        val twoNumbers = mapOf("+12125550101" to "Ines Calder", "+12125550109" to "ines calder")
        val names = ContactRanking.rank(
            listOf(reached("+12125550101", 1.0), reached("+12125550109", 1.0)),
            emptyList(),
            now,
            8,
        ) { twoNumbers[it] }
        assertThat(names).containsExactly("Ines Calder")
    }

    @Test
    fun includesStarredContacts() {
        assertThat(rank(emptyList(), starred = listOf("Lena Ortiz"))).containsExactly("Lena Ortiz")
    }

    @Test
    fun aStarIsOnlyALightBoost() {
        // A star never lifts a contact above someone reached a couple of months ago...
        val names = rank(
            listOf(reached("+12125550101", 60.0), reached("+12125550102", 2.0)),
            starred = listOf("Lena Ortiz"),
            limit = 2,
        )
        assertThat(names).containsExactly("Tomas Reyes", "Ines Calder").inOrder()

        // ...but it does settle a tie between two people reached equally.
        val tied = rank(
            listOf(reached("+12125550101", 5.0), reached("+12125550102", 5.0)),
            starred = listOf("Tomas Reyes"),
        )
        assertThat(tied).containsExactly("Tomas Reyes", "Ines Calder").inOrder()
    }

    @Test
    fun moreFavouritesThanPlacesNamesNoneOfThem() {
        val favourites = (1..40).map { "Favourite ${'A' + it % 26}${'a' + it / 26}" }
        assertThat(rank(emptyList(), starred = favourites, limit = 2)).isEmpty()

        val names = rank(
            listOf(reached("+12125550101", 3.0), reached("+12125550102", 60.0)),
            starred = favourites,
            limit = 2,
        )
        assertThat(names).containsExactly("Ines Calder", "Tomas Reyes").inOrder()
    }

    @Test
    fun aFewFavouritesFillThePlacesPeopleLeaveFree() {
        val names = rank(
            listOf(reached("+12125550101", 3.0)),
            starred = listOf("Lena Ortiz"),
            limit = 2,
        )
        assertThat(names).containsExactly("Ines Calder", "Lena Ortiz").inOrder()
    }

    @Test
    fun equalScoresGoToWhoeverWasReachedLast() {
        // Dates in the future all count as today, so both score exactly 1.0; alphabetical
        // order would pick Ines Calder.
        val names = rank(
            listOf(Exchange("+12125550101", now + day), Exchange("+12125550102", now + 2 * day)),
            limit = 1,
        )
        assertThat(names).containsExactly("Tomas Reyes")
    }

    @Test
    fun aStarredContactWhoIsAlsoReachedIsNamedOnceAndRanksHigher() {
        val names = rank(
            listOf(reached("+12125550101", 1.0), reached("+12125550102", 1.0)),
            starred = listOf("Tomas Reyes"),
        )
        assertThat(names).containsExactly("Tomas Reyes", "Ines Calder").inOrder()
    }

    @Test
    fun sendsNoNamesWithoutAnySignal() {
        // No calls, texts or stars: no prompt at all, never arbitrary names from the book.
        assertThat(rank(emptyList())).isEmpty()
        // Numbers that belong to nobody in the contacts are no signal either.
        assertThat(rank(listOf(reached("+12125550199", 1.0), reached("+12125550198", 2.0)))).isEmpty()
    }

    @Test
    fun skipsShortCodesAndNamesWithDigits() {
        val looked = mutableListOf<String>()
        val names = ContactRanking.rank(
            listOf(
                reached("55501", 1.0),
                reached("55501", 1.0),
                reached("+12125550106", 1.0),
                reached("+12125550104", 2.0),
            ),
            starred = listOf("Pharmacy 2"),
            nowMillis = now,
            limit = 8,
        ) { number ->
            looked += number
            nameOf(number)
        }
        assertThat(names).containsExactly("Jonah Pike")
        assertThat(looked).doesNotContain("55501")
    }

    @Test
    fun capsTheNamesAndTheLookups() {
        val many = (10..99).map { reached("+121255501$it", it.toDouble()) }
        val allKnown = many.associate { it.number to "Person ${'A' + it.number.takeLast(2).toInt() % 26}" }

        var lookups = 0
        val capped = ContactRanking.rank(many, emptyList(), now, 3) {
            lookups++
            allKnown[it]
        }
        assertThat(capped).hasSize(3)
        assertThat(lookups).isAtMost(3 * ContactRanking.LOOKUPS_PER_NAME)

        // Nobody known: the lookups still stop at the budget rather than walking every number.
        lookups = 0
        assertThat(
            ContactRanking.rank(many, emptyList(), now, 3) {
                lookups++
                null
            },
        ).isEmpty()
        assertThat(lookups).isEqualTo(3 * ContactRanking.LOOKUPS_PER_NAME)
    }

    @Test
    fun aFutureDateCountsAsToday() {
        // A clock that was wrong when the text arrived must not give it more than full weight.
        val names = rank(
            listOf(
                Exchange("+12125550101", now + 365 * day),
                reached("+12125550102", 0.0),
                reached("+12125550102", 0.0),
            ),
        )
        assertThat(names).containsExactly("Tomas Reyes", "Ines Calder").inOrder()
    }
}
