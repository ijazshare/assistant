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
        assertThat(ContactMatch.isSelf("Me.")).isTrue()
        assertThat(ContactMatch.isSelf("my  phone")).isTrue()
        assertThat(ContactMatch.isSelf("mum")).isFalse()
        assertThat(ContactMatch.isSelf("Meera")).isFalse()
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

    @Test
    fun `a note tags a contact only by a whole line`() {
        assertThat(ContactMatch.taggedAs("Assistant", "my assistant")).isTrue()
        assertThat(ContactMatch.taggedAs("Assistant", "assistant")).isTrue()
        assertThat(ContactMatch.taggedAs("met at the conference\nPlumber", "plumber")).isTrue()
        // A word inside a sentence is not a tag: "text Sam" must not reach Sam's dad.
        assertThat(ContactMatch.taggedAs("Sam's dad", "Sam")).isFalse()
        assertThat(ContactMatch.taggedAs("Assistant manager at the bank", "assistant")).isFalse()
        assertThat(ContactMatch.taggedAs("Assistant", "my")).isFalse()
    }

    @Test
    fun `a saved note line matches however Whisper spaces the name`() {
        assertThat(ContactMatch.taggedAs("be lol", "Be-lol")).isTrue()
        assertThat(ContactMatch.taggedAs("be lol", "Belol.")).isTrue()
        assertThat(ContactMatch.tagLine("My Assistant.")).isEqualTo("assistant")
    }

    @Test
    fun `a misheard name sounds like the contact's`() {
        assertThat(ContactMatch.soundsAlike("Be lol", "Tariq Bilal")).isTrue()
        assertThat(ContactMatch.soundsAlike("zoo bear", "Zubair Noor")).isTrue()
        assertThat(ContactMatch.soundsAlike("fa reed", "Farid")).isTrue()
    }

    @Test
    fun `a different or too short name does not sound alike`() {
        assertThat(ContactMatch.soundsAlike("Bill", "Bilal")).isFalse()
        assertThat(ContactMatch.soundsAlike("Sam", "Samuel Ortiz")).isFalse()
        assertThat(ContactMatch.soundsAlike("the", "Teo")).isFalse()
    }

    @Test
    fun `Ml is Molana, and saying it is optional either way`() {
        assertThat(ContactMatch.matches("molana idris", "Ml Idris Karim")).isTrue()
        assertThat(ContactMatch.matches("Maulana Idris Karim", "Ml Idris Karim")).isTrue()
        assertThat(ContactMatch.matches("idris karim", "Ml Idris Karim")).isTrue()
        assertThat(ContactMatch.matches("molana idris", "Idris Karim")).isTrue()
        assertThat(ContactMatch.matches("ml idris", "Maulana Idris")).isTrue()
    }

    @Test
    fun `the title alone names only a contact that has it`() {
        assertThat(ContactMatch.matches("molana", "Ml Idris Karim")).isTrue()
        assertThat(ContactMatch.matches("molana", "Idris Karim")).isFalse()
        assertThat(ContactMatch.matches("molana yusuf", "Ml Idris Karim")).isFalse()
    }

    @Test
    fun `a name misheard word by word, with the title said, sounds alike`() {
        // The shape of the owner's first real mishearing, with made-up names.
        assertThat(ContactMatch.soundsAlike("Molana Rahid Mia", "Ml Rohed Miah")).isTrue()
        assertThat(ContactMatch.soundsAlike("Molana Rehidmia", "Ml Rohed Miah")).isTrue()
        assertThat(ContactMatch.soundsAlike("Molana", "Ml Rohed Miah")).isFalse()
        assertThat(ContactMatch.soundsAlike("Rahid Lee", "Ml Rohed Miah")).isFalse()
    }

    @Test
    fun `a saved line leaves the title out, so either way of saying it finds the contact`() {
        assertThat(ContactMatch.tagLine("Molana Rahid Mia")).isEqualTo("rahid mia")
        assertThat(ContactMatch.taggedAs("rahid mia", "Maulana Rahid Mia")).isTrue()
        assertThat(ContactMatch.taggedAs("rahid mia", "Rahid Mia")).isTrue()
    }
}
