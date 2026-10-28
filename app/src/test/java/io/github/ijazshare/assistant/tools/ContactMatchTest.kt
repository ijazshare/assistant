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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContactMatchTest {

    @Test
    fun matchesAFirstName() {
        assertThat(ContactMatch.matches("osman", "Osman Khan")).isTrue()
    }

    @Test
    fun matchesTheFullName() {
        assertThat(ContactMatch.matches("john smith", "John Smith")).isTrue()
    }

    @Test
    fun matchesALastName() {
        assertThat(ContactMatch.matches("khan", "Osman Khan")).isTrue()
    }

    @Test
    fun rejectsTheFatherInLawBug() {
        // The exact failure: "me" must never resolve to "MI Aziz".
        assertThat(ContactMatch.matches("me", "MI Aziz")).isFalse()
    }

    @Test
    fun rejectsALooseOverlap() {
        assertThat(ContactMatch.matches("sam", "Samantha Jones")).isFalse()
    }

    @Test
    fun matchesCaseInsensitivelyIncludingNoise() {
        // The real contact from testing, with its "(Father In Law)" note.
        assertThat(ContactMatch.matches("ml aziz", "Ml Aziz (Father In Law)")).isTrue()
        assertThat(ContactMatch.matches("Ml Aziz", "Ml Aziz (Father In Law)")).isTrue()
    }

    @Test
    fun distinguishesNearMisspellings() {
        // "MI" (capital i) must not match "Ml" (lowercase L) — different people.
        assertThat(ContactMatch.matches("MI Aziz", "Ml Aziz")).isFalse()
    }

    @Test
    fun treatsMeAsSelf() {
        assertThat(ContactMatch.isSelf("me")).isTrue()
        assertThat(ContactMatch.isSelf("Myself")).isTrue()
        assertThat(ContactMatch.isSelf("mum")).isFalse()
    }

    @Test
    fun `a number is used only if it was said`() {
        assertThat(ContactMatch.numberSaid("text 555 0123 I'm outside", "5550123")).isTrue()
        assertThat(ContactMatch.numberSaid("call five five five oh one two three", "5550123")).isTrue()
        // The model may add the country code; the user did not say it.
        assertThat(ContactMatch.numberSaid("text 555 555 0123", "+15555550123")).isTrue()
        // A number nobody said.
        assertThat(ContactMatch.numberSaid("text Sam", "5550123")).isFalse()
        assertThat(ContactMatch.numberSaid("call 555 0124", "5550123")).isFalse()
    }
}
