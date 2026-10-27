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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class LocalAnswersTest {

    private val now = ZonedDateTime.of(2026, 9, 2, 14, 5, 0, 0, NEW_YORK)

    @Test
    fun `the clock answers for the clock`() {
        // The exact question that came back reading out the notification shade.
        assertThat(LocalAnswers.of("what time is it", now)).isEqualTo("It is 2:05 PM.")
        assertThat(LocalAnswers.of("What's the time?", now)).isEqualTo("It is 2:05 PM.")
        assertThat(LocalAnswers.of("tell me the time", now)).isEqualTo("It is 2:05 PM.")
    }

    @Test
    fun `and for the date`() {
        assertThat(LocalAnswers.of("what is the date", now)).isEqualTo("It is Wednesday, 2 September.")
        assertThat(LocalAnswers.of("what day is it", now)).isEqualTo("It is Wednesday, 2 September.")
    }

    @Test
    fun `both when both are asked`() {
        assertThat(LocalAnswers.of("what is the time and date", now))
            .isEqualTo("It is 2:05 PM on Wednesday, 2 September.")
    }

    @Test
    fun `a command that merely mentions time is not a question`() {
        // These must reach the model: answering them from the clock would be worse than
        // useless, it would stop them working.
        assertThat(LocalAnswers.of("set a timer for ten minutes", now)).isNull()
        assertThat(LocalAnswers.of("what time does the shop close", now)).isNull()
        assertThat(LocalAnswers.of("remind me to call Ali today", now)).isNull()
        assertThat(LocalAnswers.of("what did I miss", now)).isNull()
        assertThat(LocalAnswers.of("read the screen", now)).isNull()
    }

    @Test
    fun `nothing at all is not a question`() {
        assertThat(LocalAnswers.of("", now)).isNull()
        assertThat(LocalAnswers.of("time", now)).isNull()
    }

    // ---- Elsewhere. The phone is in New York, at the minute the 4B made answers up. ----

    private val evening = ZonedDateTime.of(2026, 10, 26, 17, 57, 0, 0, NEW_YORK)

    private fun asked(transcript: String, at: ZonedDateTime = evening) = LocalAnswers.of(transcript, at, PLACES)

    @Test
    fun `the questions the 4B answered wrongly get the true time`() {
        // Exactly as Whisper wrote them. The model said 09:32, 09:57 and 6:57 AM.
        assertThat(asked("What time is in Johannesburg?", evening.withMinute(32)))
            .isEqualTo("It is 11:32 PM in Johannesburg.")
        assertThat(asked("What time is it in Johannesburg, South Africa?"))
            .isEqualTo("It is 11:57 PM in Johannesburg.")
        assertThat(asked("What time in Sydney, Australia?")).isEqualTo("It is 8:57 AM on Tuesday in Sydney.")
    }

    @Test
    fun `the date somewhere else`() {
        assertThat(asked("What day is it in Sydney?")).isEqualTo("It is Tuesday, 27 October in Sydney.")
        assertThat(asked("What's the date in London?")).isEqualTo("It is Monday, 26 October in London.")
        assertThat(asked("What is the date and time in Tokyo?"))
            .isEqualTo("It is 6:57 AM on Tuesday, 27 October in Tokyo.")
    }

    @Test
    fun `daylight saving moves Sydney and not the question`() {
        // Noon here both times: AEST, ten hours ahead of UTC, in July; AEDT, eleven, by October.
        val july = ZonedDateTime.of(2026, 7, 15, 12, 0, 0, 0, NEW_YORK)
        val october = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, NEW_YORK)
        assertThat(asked("what time is it in Sydney", july)).isEqualTo("It is 2:00 AM on Thursday in Sydney.")
        assertThat(asked("what time is it in Sydney", october)).isEqualTo("It is 3:00 AM on Thursday in Sydney.")
    }

    @Test
    fun `zones half and three quarters of an hour off`() {
        assertThat(asked("What time is it in Kolkata?")).isEqualTo("It is 3:27 AM on Tuesday in Kolkata.")
        assertThat(asked("What time is it in Kathmandu?")).isEqualTo("It is 3:42 AM on Tuesday in Kathmandu.")
        // By country too, where ICU spells the zone the old way, Asia/Katmandu.
        assertThat(asked("What time is it in Nepal?")).isEqualTo("It is 3:42 AM on Tuesday in Nepal.")
    }

    @Test
    fun `either side of the date line`() {
        // The same clock time a day apart. Samoa is the country; tzdb's old Pacific/Samoa is
        // Pago Pago, and taking that for the country would make it a day out.
        assertThat(asked("What time is it in Samoa?")).isEqualTo("It is 10:57 AM on Tuesday in Samoa.")
        assertThat(asked("What time is it in American Samoa?")).isEqualTo("It is 10:57 AM in American Samoa.")
        assertThat(asked("What day is it in Samoa?")).isEqualTo("It is Tuesday, 27 October in Samoa.")
    }

    @Test
    fun `a place still on yesterday`() {
        val pastMidnight = ZonedDateTime.of(2026, 10, 27, 0, 30, 0, 0, NEW_YORK)
        assertThat(asked("What time is it in Los Angeles?", pastMidnight))
            .isEqualTo("It is 9:30 PM on Monday in Los Angeles.")
        assertThat(asked("What's the date in Honolulu?", pastMidnight))
            .isEqualTo("It is Monday, 26 October in Honolulu.")
    }

    @Test
    fun `a country or state with one time is answered, one with several asks for a city`() {
        assertThat(asked("What time is it in South Africa?")).isEqualTo("It is 11:57 PM in South Africa.")
        assertThat(asked("What time is it in California?")).isEqualTo("It is 2:57 PM in California.")
        assertThat(asked("What time is it in Australia?"))
            .isEqualTo("Australia has more than one time zone. Which city?")
        assertThat(asked("what time is it in the US")).isEqualTo("The US has more than one time zone. Which city?")
        assertThat(asked("What time is it in Texas?")).isEqualTo("Texas has more than one time zone. Which city?")
        // A name two places share is no better: a state and a country, a state and a capital.
        assertThat(asked("What time is it in Georgia?")).isEqualTo("Georgia has more than one time zone. Which city?")
        assertThat(asked("What time is it in Washington?"))
            .isEqualTo("Washington has more than one time zone. Which city?")
    }

    @Test
    fun `a city named with its country or state is the city`() {
        assertThat(asked("What time is it in Perth, Australia?")).isEqualTo("It is 5:57 AM on Tuesday in Perth.")
        assertThat(asked("what time is it in Johannesburg South Africa")).isEqualTo("It is 11:57 PM in Johannesburg.")
        // ICU calls India's zone Asia/Calcutta; the alias says Asia/Kolkata. Same zone.
        assertThat(asked("What time is it in Mumbai, India?")).isEqualTo("It is 3:27 AM on Tuesday in Mumbai.")
        // And the qualifier settles which Georgia.
        assertThat(asked("What time is it in Atlanta, Georgia?")).isEqualTo("It is 5:57 PM in Atlanta.")
        assertThat(asked("What time is it in Georgia, USA?")).isEqualTo("It is 5:57 PM in Georgia.")
    }

    @Test
    fun `a town with no zone of its own keeps its country's only time`() {
        assertThat(asked("What time is it in Faisalabad, Pakistan?"))
            .isEqualTo("It is 2:57 AM on Tuesday in Faisalabad, Pakistan.")
        assertThat(asked("What time is it in Portland, Maine?")).isEqualTo("It is 5:57 PM in Portland, Maine.")
        // Oregon has two, and nothing says which Portland is on.
        assertThat(asked("What time is it in Portland, Oregon?"))
            .isEqualTo("I don't know the time zone for Portland, Oregon.")
    }

    @Test
    fun `a city its country contradicts is declined`() {
        // Not the London and the Perth the phone knows, and nothing to say which they are.
        assertThat(asked("What time is it in London, Ontario?"))
            .isEqualTo("I don't know the time zone for London, Ontario.")
        assertThat(asked("What time is it in Perth, Scotland?"))
            .isEqualTo("I don't know the time zone for Perth, Scotland.")
        assertThat(asked("What time is it in Sydney, New South Wales?"))
            .isEqualTo("I don't know the time zone for Sydney, New South Wales.")
    }

    @Test
    fun `a place the phone does not know is declined plainly`() {
        assertThat(asked("What time is it in Narnia?")).isEqualTo("I don't know the time zone for Narnia.")
        assertThat(asked("What's the date in Atlantis?")).isEqualTo("I don't know the time zone for Atlantis.")
        // Ending in a country's name does not put a place in it: New England keeps
        // Boston's time, not England's.
        assertThat(asked("What time is it in New England?"))
            .isEqualTo("I don't know the time zone for New England.")
        assertThat(asked("What time is it in New South Wales?"))
            .isEqualTo("I don't know the time zone for New South Wales.")
        assertThat(asked("What time is it in South America?"))
            .isEqualTo("I don't know the time zone for South America.")
    }

    @Test
    fun `the way people actually say it`() {
        assertThat(asked("What's the time in Tokyo right now?")).isEqualTo("It is 6:57 AM on Tuesday in Tokyo.")
        assertThat(asked("what is the current time in London")).isEqualTo("It is 9:57 PM in London.")
        assertThat(asked("What time is it in Washington, D.C.?")).isEqualTo("It is 5:57 PM in Washington, D.C.")
        assertThat(asked("What time is it in São Paulo?")).isEqualTo("It is 6:57 PM in São Paulo.")
        assertThat(asked("what time is it in sao paulo")).isEqualTo("It is 6:57 PM in sao paulo.")
        assertThat(asked("What time is it in the UK?")).isEqualTo("It is 9:57 PM in the UK.")
    }

    @Test
    fun `other questions and commands that mention a place or a time still go to the model`() {
        assertThat(asked("set an alarm for 7 in the morning")).isNull()
        assertThat(asked("set a timer for ten minutes")).isNull()
        assertThat(asked("remind me in an hour to call the office")).isNull()
        assertThat(asked("what time does the pharmacy close")).isNull()
        assertThat(asked("what time does the pharmacy in London close")).isNull()
        assertThat(asked("how much time is left on my timer")).isNull()
        assertThat(asked("what time is my alarm set for")).isNull()
        assertThat(asked("what time is sunset")).isNull()
        assertThat(asked("what time is sunset in Sydney")).isNull()
        assertThat(asked("text the office that I land in Sydney at nine")).isNull()
        assertThat(asked("send a message saying the meeting in London is at noon")).isNull()
        // The question's own shape, but no place after "in".
        assertThat(asked("what time is it in five minutes")).isNull()
        assertThat(asked("what is the date in 3 weeks")).isNull()
        assertThat(asked("what time is it in Spanish")).isNull()
        assertThat(asked("what time is it in military time")).isNull()
        assertThat(asked("what is the date in the Islamic calendar")).isNull()
        assertThat(asked("what time is it in London when it is noon here")).isNull()
        assertThat(asked("what time is it in")).isNull()
    }

    @Test
    fun `places are looked up by their English names whatever the phone's language`() {
        // On a phone in Spanish, South Africa is "Sudáfrica" unless English is asked for,
        // and "Spanish" is "español". The clock's formatters are pinned to English too, but
        // they are built once, so changing the locale under them here would prove nothing.
        val before = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        try {
            val places = Places { ZONES[it].orEmpty() }
            assertThat(LocalAnswers.of("What time is it in South Africa?", evening, places))
                .isEqualTo("It is 11:57 PM in South Africa.")
            assertThat(LocalAnswers.of("what time is it in Spanish", evening, places)).isNull()
        } finally {
            Locale.setDefault(before)
        }
    }

    private companion object {
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

        /**
         * What ICU tells the phone about these countries' zones, cut down to the ones the
         * tests use, and spelled ICU's way (Asia/Calcutta, Asia/Katmandu).
         */
        val ZONES = mapOf(
            "AU" to listOf(
                "Australia/Sydney",
                "Australia/Melbourne",
                "Australia/Brisbane",
                "Australia/Adelaide",
                "Australia/Darwin",
                "Australia/Perth",
                "Australia/Hobart",
            ),
            "US" to listOf(
                "America/New_York", "America/Detroit", "America/Chicago", "America/Denver", "America/Phoenix",
                "America/Los_Angeles", "America/Anchorage", "America/Adak", "Pacific/Honolulu",
            ),
            "GB" to listOf("Europe/London"),
            "GE" to listOf("Asia/Tbilisi"),
            "IN" to listOf("Asia/Calcutta"),
            "NP" to listOf("Asia/Katmandu"),
            "PK" to listOf("Asia/Karachi"),
            "WS" to listOf("Pacific/Apia"),
            "AS" to listOf("Pacific/Pago_Pago"),
            "ZA" to listOf("Africa/Johannesburg"),
        )

        val PLACES = Places { ZONES[it].orEmpty() }
    }
}
