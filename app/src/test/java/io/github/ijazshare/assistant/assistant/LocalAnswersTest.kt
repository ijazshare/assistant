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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.zone.ZoneRulesProvider
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
        // Asked for as a whole question: the session ends after this, and "Sydney" said on
        // its own next time would reach the model with nothing to say what it answers.
        assertThat(asked("What time is it in Australia?"))
            .isEqualTo("Australia has more than one time zone. Ask me about a city there.")
        // A city said on its own is no question, and still goes on to the model.
        val replies = listOf(
            "Sydney",
            "Sydney.",
            "Sydney, please.",
            "Melbourne.",
            "Sydney, Australia.",
            "In Sydney.",
            "What about Sydney?",
            "Atlanta",
        )
        for (reply in replies) assertThat(asked(reply)).isNull()
        assertThat(asked("what time is it in the US"))
            .isEqualTo("The US has more than one time zone. Ask me about a city there.")
        assertThat(asked("What time is it in Texas?"))
            .isEqualTo("Texas has more than one time zone. Ask me about a city there.")
    }

    @Test
    fun `a name two places share says so, rather than that either has two zones`() {
        // A state and a country, a state and a capital: each has one zone of its own.
        assertThat(asked("What time is it in Georgia?"))
            .isEqualTo("There is more than one Georgia. Ask me about a city in the one you mean.")
        assertThat(asked("What time is it in Washington?"))
            .isEqualTo("There is more than one Washington. Ask me about a city in the one you mean.")
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
        assertThat(asked("set an alarm for 7 London time")).isNull()
        assertThat(asked("set my clock to Tokyo time")).isNull()
        assertThat(asked("remind me to call mom when it's 9 in London")).isNull()
        // The question's own shape, but no place after "in".
        assertThat(asked("what time is it in five minutes")).isNull()
        assertThat(asked("what is the date in 3 weeks")).isNull()
        assertThat(asked("what time is it in Spanish")).isNull()
        assertThat(asked("what time is it in military time")).isNull()
        assertThat(asked("what is the date in the Islamic calendar")).isNull()
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
            val places = Places(TZDB) { ZONES[it].orEmpty() }
            assertThat(LocalAnswers.of("What time is it in South Africa?", evening, places))
                .isEqualTo("It is 11:57 PM in South Africa.")
            assertThat(LocalAnswers.of("what time is it in Spanish", evening, places)).isNull()
        } finally {
            Locale.setDefault(before)
        }
    }

    // ---- What the review of the first version found, each kept as it was reported. ----

    @Test
    fun `an offset from UTC is that offset, never plain UTC`() {
        assertThat(asked("What time is it in UTC+2?")).isEqualTo("It is 11:57 PM in UTC+2.")
        assertThat(asked("What time is it in GMT-5?")).isEqualTo("It is 4:57 PM in GMT-5.")
        assertThat(asked("What's the time in UTC+05:30?")).isEqualTo("It is 3:27 AM on Tuesday in UTC+05:30.")
        assertThat(asked("What time is it in GMT+1?")).isEqualTo("It is 10:57 PM in GMT+1.")
        assertThat(asked("What time is it in UTC−5?")).isEqualTo("It is 4:57 PM in UTC−5.")
        assertThat(asked("What's the time in GMT+8 right now?")).isEqualTo("It is 5:57 AM on Tuesday in GMT+8.")
        assertThat(asked("What is the date in UTC+14?")).isEqualTo("It is Tuesday, 27 October in UTC+14.")
        assertThat(asked("What time is it in UTC plus 3?")).isEqualTo("It is 12:57 AM on Tuesday in UTC plus 3.")
        // Plain UTC is still UTC.
        assertThat(asked("What time is it in UTC?")).isEqualTo("It is 9:57 PM in UTC.")
        // No clock is set this far off, and an offset spelled out in words is not read.
        assertThat(asked("What time is it in UTC+15?")).isEqualTo("I don't know the time zone for UTC+15.")
        assertThat(asked("What time is it in UTC plus two?"))
            .isEqualTo("I don't know the time zone for UTC plus two.")
        // Digits never leave the letters to stand for a place on their own.
        assertThat(asked("What time is it in UTC 2?")).isNull()
    }

    @Test
    fun `a name a remote zone shares is not given that zone's time`() {
        // Antarctic stations, uninhabited atolls and the Falklands' capital: other places are
        // better known by these names. Declined, not given the station's or the island's time.
        assertThat(asked("What time is it in Davis?")).isEqualTo("I don't know the time zone for Davis.")
        assertThat(asked("What time is it in Palmer?")).isEqualTo("I don't know the time zone for Palmer.")
        assertThat(asked("What time is it in Casey?")).isEqualTo("I don't know the time zone for Casey.")
        assertThat(asked("What time is it in Stanley?")).isEqualTo("I don't know the time zone for Stanley.")
        assertThat(asked("What time is it in Midway?")).isEqualTo("I don't know the time zone for Midway.")
        assertThat(asked("What time is it in South Georgia?"))
            .isEqualTo("I don't know the time zone for South Georgia.")
        // Names several places share ask which one.
        for (name in listOf("Norfolk", "Chatham", "Jersey", "Córdoba", "Cordoba", "Mérida")) {
            assertThat(asked("What time is it in $name?"))
                .isEqualTo("There is more than one $name. Ask me about a city in the one you mean.")
        }
        val winter = ZonedDateTime.of(2027, 1, 15, 12, 0, 0, 0, NEW_YORK)
        assertThat(asked("What time is it in Norfolk?", winter))
            .isEqualTo("There is more than one Norfolk. Ask me about a city in the one you mean.")
        assertThat(asked("What time is it in New Jersey?")).isEqualTo("It is 5:57 PM in New Jersey.")
    }

    @Test
    fun `a shared name settles with its country or state`() {
        assertThat(asked("What time is it in Norfolk, Virginia?")).isEqualTo("It is 5:57 PM in Norfolk.")
        assertThat(asked("What time is it in Norfolk, England?")).isEqualTo("It is 9:57 PM in Norfolk.")
        assertThat(asked("What time is it in Norfolk Island?"))
            .isEqualTo("It is 9:57 AM on Tuesday in Norfolk Island.")
        assertThat(asked("What time is it in Chatham, Massachusetts?")).isEqualTo("It is 5:57 PM in Chatham.")
        assertThat(asked("What time is it in Córdoba, Spain?")).isEqualTo("It is 10:57 PM in Córdoba.")
        assertThat(asked("What time is it in Córdoba, Argentina?")).isEqualTo("It is 6:57 PM in Córdoba.")
        assertThat(asked("What time is it in Mérida, Mexico?")).isEqualTo("It is 3:57 PM in Mérida.")
        assertThat(asked("What time is it in Mérida, Venezuela?")).isEqualTo("It is 5:57 PM in Mérida.")
    }

    @Test
    fun `a town takes the time only of a country or state, not of a city that may be a namesake`() {
        for (town in listOf("Norwich", "Cromer", "King's Lynn", "Virginia Beach")) {
            assertThat(asked("What time is it in $town, Norfolk?"))
                .isEqualTo("I don't know the time zone for $town, Norfolk.")
        }
        assertThat(asked("What time is it in Lucena, Córdoba?"))
            .isEqualTo("I don't know the time zone for Lucena, Córdoba.")
        assertThat(asked("What time is it in Brooklyn, New York?")).isEqualTo("It is 5:57 PM in Brooklyn, New York.")
        // Davis is no longer an Antarctic station, so with its state it is California's.
        assertThat(asked("What time is it in Davis, California?")).isEqualTo("It is 2:57 PM in Davis, California.")
        // A state with a town on another zone cannot vouch for its towns' time.
        assertThat(asked("What time is it in West Wendover, Nevada?"))
            .isEqualTo("I don't know the time zone for West Wendover, Nevada.")
        assertThat(asked("What time is it in Las Vegas, Nevada?")).isEqualTo("It is 2:57 PM in Las Vegas.")
    }

    @Test
    fun `a zone whose clocks changed after the phone's data is declined`() {
        // Morocco left +01 for good on 20 September (tzdata 2026c); Alberta (2026c), the
        // Northwest Territories and Manitoba (2026e) stop falling back on 1 November. Data
        // older than that gives the old rules' time, so the phone says why it won't.
        val old = Places("2026b") { ZONES[it].orEmpty() }
        val november = ZonedDateTime.of(2026, 11, 2, 12, 0, 0, 0, NEW_YORK)
        for (place in listOf("Casablanca", "Morocco")) {
            assertThat(LocalAnswers.of("What time is it in $place?", evening, old))
                .isEqualTo("My time-zone data for $place is out of date, so I can't be sure of the time there.")
        }
        for (place in listOf("Calgary", "Alberta", "Winnipeg", "Manitoba", "Yellowknife", "Northwest Territories")) {
            assertThat(LocalAnswers.of("What time is it in $place?", november, old))
                .isEqualTo("My time-zone data for $place is out of date, so I can't be sure of the time there.")
        }
        // British Columbia's change came in 2026b, so that data has it: -07 from November.
        assertThat(LocalAnswers.of("What time is it in Vancouver?", november, old))
            .isEqualTo("It is 10:00 AM in Vancouver.")
        assertThat(LocalAnswers.of("What time is it in Vancouver?", november, Places("2026a") { ZONES[it].orEmpty() }))
            .isEqualTo("My time-zone data for Vancouver is out of date, so I can't be sure of the time there.")
        // Up-to-date data answers. Not checked against a time here: this JVM's own data is
        // 2026b, so whatever it says for Casablanca is the old rules'.
        assertThat(LocalAnswers.of("What time is it in Casablanca?", evening, Places("2026e") { ZONES[it].orEmpty() }))
            .startsWith("It is ")
        // Data whose version cannot be read is taken to be old.
        assertThat(LocalAnswers.of("What time is it in Casablanca?", evening, Places("") { ZONES[it].orEmpty() }))
            .startsWith("My time-zone data")
    }

    @Test
    fun `the polite ways of asking are answered too`() {
        assertThat(asked("Can you tell me the time in Tokyo?")).isEqualTo("It is 6:57 AM on Tuesday in Tokyo.")
        assertThat(asked("Can you tell me what time it is in Tokyo?")).isEqualTo("It is 6:57 AM on Tuesday in Tokyo.")
        assertThat(asked("Could you tell me what time it is in London?")).isEqualTo("It is 9:57 PM in London.")
        assertThat(asked("Could you tell me the time in Sydney, please?"))
            .isEqualTo("It is 8:57 AM on Tuesday in Sydney.")
        assertThat(asked("Do you know what time it is in Dubai?")).isEqualTo("It is 1:57 AM on Tuesday in Dubai.")
        assertThat(asked("Do you know the time in London?")).isEqualTo("It is 9:57 PM in London.")
        assertThat(asked("Please tell me the time in Dubai.")).isEqualTo("It is 1:57 AM on Tuesday in Dubai.")
        assertThat(asked("I want to know the time in Karachi.")).isEqualTo("It is 2:57 AM on Tuesday in Karachi.")
        assertThat(asked("I'd like to know the time in Karachi.")).isEqualTo("It is 2:57 AM on Tuesday in Karachi.")
    }

    @Test
    fun `fillers, terse questions and other everyday wordings are answered`() {
        val tokyo = "It is 6:57 AM on Tuesday in Tokyo."
        val london = "It is 9:57 PM in London."
        assertThat(asked("Hey, what time is it in Sydney?")).isEqualTo("It is 8:57 AM on Tuesday in Sydney.")
        assertThat(asked("Hey Machine, what time is it in London?")).isEqualTo(london)
        assertThat(asked("Okay, what's the time in Paris?")).isEqualTo("It is 10:57 PM in Paris.")
        assertThat(asked("Um, what time is it in Tokyo?")).isEqualTo(tokyo)
        assertThat(asked("So what's the time in Lagos?")).isEqualTo("It is 10:57 PM in Lagos.")
        assertThat(asked("Time in Tokyo?")).isEqualTo(tokyo)
        assertThat(asked("Time in London right now?")).isEqualTo(london)
        assertThat(asked("Current time in London.")).isEqualTo(london)
        assertThat(asked("Current time in Sydney, Australia.")).isEqualTo("It is 8:57 AM on Tuesday in Sydney.")
        assertThat(asked("Local time in Tokyo.")).isEqualTo(tokyo)
        assertThat(asked("Date in Tokyo?")).isEqualTo("It is Tuesday, 27 October in Tokyo.")
        assertThat(asked("Tokyo time.")).isEqualTo(tokyo)
        assertThat(asked("What's London time?")).isEqualTo(london)
        assertThat(asked("What time is it in Tokyo time?")).isEqualTo(tokyo)
        assertThat(asked("What time is it over in London?")).isEqualTo(london)
        assertThat(asked("What time is it there in Tokyo?")).isEqualTo(tokyo)
        assertThat(asked("What time is it at the moment in Tokyo?")).isEqualTo(tokyo)
        assertThat(asked("What hour is it in Tokyo?")).isEqualTo(tokyo)
        assertThat(asked("What time do they have in Tokyo?")).isEqualTo(tokyo)
        assertThat(asked("What time would it be in Tokyo right now?")).isEqualTo(tokyo)
        assertThat(asked("What's the time like in Tokyo?")).isEqualTo(tokyo)
        assertThat(asked("In London, what time is it?")).isEqualTo(london)
        assertThat(asked("In Tokyo what time is it")).isEqualTo(tokyo)
        assertThat(asked("What day of the week is it in Tokyo?")).isEqualTo("It is Tuesday, 27 October in Tokyo.")
        // The same forms as the answers review listed them, all of Tokyo.
        val forms = listOf(
            "Current time in Tokyo", "Time in Tokyo", "Tokyo time", "Do you know what time it is in Tokyo?",
            "Hey, what time is it in Tokyo?", "Okay, what time is it in Tokyo?", "So what time is it in Tokyo?",
            "What time is it over in Tokyo?", "In Tokyo, what time is it?",
        )
        for (form in forms) assertThat(asked(form)).isEqualTo(tokyo)
        // Terse, but about nowhere: still nobody's world clock.
        assertThat(asked("Today in history")).isNull()
        assertThat(asked("Time in a bottle")).isNull()
    }

    @Test
    fun `the US zones by name`() {
        assertThat(asked("What time is it in Pacific time?")).isEqualTo("It is 2:57 PM in Pacific time.")
        assertThat(asked("What time is it in Eastern time?")).isEqualTo("It is 5:57 PM in Eastern time.")
        assertThat(asked("What time is it in Central time?")).isEqualTo("It is 4:57 PM in Central time.")
        assertThat(asked("What time is it in Mountain time?")).isEqualTo("It is 3:57 PM in Mountain time.")
        assertThat(asked("What time is it on the West Coast?")).isEqualTo("It is 2:57 PM on the West Coast.")
        assertThat(asked("What time is it on the East Coast?")).isEqualTo("It is 5:57 PM on the East Coast.")
        // A standard time's name is an hour off its zone while the clocks are forward, and
        // other zones' names are not the phone's: declined, never handed on.
        assertThat(asked("What time is it in PST?")).isEqualTo("I don't know the time zone for PST.")
        assertThat(asked("What time is it in EST?")).isEqualTo("I don't know the time zone for EST.")
        assertThat(asked("What time is it in Eastern Standard Time?"))
            .isEqualTo("I don't know the time zone for Eastern Standard Time.")
        assertThat(asked("What time is it in Central European Time?"))
            .isEqualTo("I don't know the time zone for Central European Time.")
    }

    @Test
    fun `how far apart two clocks are is the same fact`() {
        // The week Europe has left summer time and the US has not: four hours, not five.
        assertThat(asked("What's the time difference between London and New York?"))
            .isEqualTo("London is 4 hours ahead of New York right now.")
        assertThat(asked("What is the time difference between here and Dubai?"))
            .isEqualTo("Dubai is 8 hours ahead of you right now.")
        assertThat(asked("How many hours ahead is London?")).isEqualTo("London is 4 hours ahead of you right now.")
        assertThat(asked("How far ahead is Sydney?")).isEqualTo("Sydney is 15 hours ahead of you right now.")
        assertThat(asked("What's the time difference in Tokyo?")).isEqualTo("Tokyo is 13 hours ahead of you right now.")
        assertThat(asked("How many hours difference is there between New York and Karachi?"))
            .isEqualTo("New York is 9 hours behind Karachi right now.")
        assertThat(asked("Is London five hours ahead?")).isEqualTo("London is 4 hours ahead of you right now.")
        assertThat(asked("What's the time difference with Pakistan?"))
            .isEqualTo("Pakistan is 9 hours ahead of you right now.")
        assertThat(asked("What's the time difference with Kolkata?"))
            .isEqualTo("Kolkata is 9 hours and 30 minutes ahead of you right now.")
        assertThat(asked("How far behind is Los Angeles?"))
            .isEqualTo("Los Angeles is 3 hours behind you right now.")
        assertThat(asked("What's the time difference between London and Australia?"))
            .isEqualTo("Australia has more than one time zone. Ask me about a city there.")
        val november = ZonedDateTime.of(2026, 11, 2, 17, 57, 0, 0, NEW_YORK)
        assertThat(asked("What's the time difference between London and New York?", november))
            .isEqualTo("London is 5 hours ahead of New York right now.")
        // As far ahead as a road, not a clock.
        assertThat(asked("How far ahead is the next exit?")).isNull()
    }

    @Test
    fun `the time somewhere at another time is declined, not guessed`() {
        val notNow = "I can only tell you the time as it is right now, not work it out for another time."
        assertThat(asked("If it's 3 PM here, what time is it in London?")).isEqualTo(notNow)
        assertThat(asked("What time is it in London when it's noon in New York?")).isEqualTo(notNow)
        assertThat(asked("What time is it in Tokyo, if it's noon in London?")).isEqualTo(notNow)
        assertThat(asked("what time is it in London when it is noon here")).isEqualTo(notNow)
    }

    @Test
    fun `a time before a comma is not a town`() {
        assertThat(asked("What time is it in an hour, London?")).isNull()
        assertThat(asked("What time is it in 5 minutes, London?")).isNull()
        assertThat(asked("What's the date in 3 days, Tokyo?")).isNull()
        assertThat(asked("What is the date in two weeks, Tokyo?")).isNull()
        assertThat(asked("What time is it in the morning, London?")).isNull()
    }

    @Test
    fun `a country named before its city is named as the city`() {
        assertThat(asked("What time is it in Australia, Perth?")).isEqualTo("It is 5:57 AM on Tuesday in Perth.")
        assertThat(asked("What time is it in Russia, Moscow?")).isEqualTo("It is 12:57 AM on Tuesday in Moscow.")
        assertThat(asked("What time is it in USA, New York?")).isEqualTo("It is 5:57 PM in New York.")
        assertThat(asked("What time is it in Canada, Toronto?")).isEqualTo("It is 5:57 PM in Toronto.")
        assertThat(asked("What time is it in Brazil, Sao Paulo?")).isEqualTo("It is 6:57 PM in Sao Paulo.")
        assertThat(asked("What time is it in Georgia, Atlanta?")).isEqualTo("It is 5:57 PM in Atlanta.")
    }

    @Test
    fun `words after the place do not make it unknown`() {
        val tokyo = "It is 6:57 AM on Tuesday in Tokyo."
        assertThat(asked("What time is it in Tokyo? Thank you.")).isEqualTo(tokyo)
        assertThat(asked("What time is it in Tokyo, thanks.")).isEqualTo(tokyo)
        assertThat(asked("What time is it in Tokyo again?")).isEqualTo(tokyo)
        assertThat(asked("What time is it in Tokyo exactly?")).isEqualTo(tokyo)
        assertThat(asked("What time is it in Tokyo, by the way?")).isEqualTo(tokyo)
        assertThat(asked("What time is it in Tokyo in Japan?")).isEqualTo(tokyo)
        assertThat(asked("Say the time in Tokyo out loud.")).isEqualTo(tokyo)
        // Two places get two answers; together, Trinidad and Tobago are still one.
        assertThat(asked("What time is it in Tokyo and London?")).isEqualTo("$tokyo It is 9:57 PM in London.")
        assertThat(asked("What's the time in Tokyo vs London?")).isEqualTo("$tokyo It is 9:57 PM in London.")
        assertThat(asked("What time is it in Trinidad and Tobago?")).isEqualTo("It is 5:57 PM in Trinidad and Tobago.")
    }

    @Test
    fun `common places that were declined are answered`() {
        assertThat(asked("What time is it in Korea?")).isEqualTo("It is 6:57 AM on Tuesday in Korea.")
        assertThat(asked("What time is it in Seoul, Korea?")).isEqualTo("It is 6:57 AM on Tuesday in Seoul.")
        assertThat(asked("What time is it in Saudi?")).isEqualTo("It is 12:57 AM on Tuesday in Saudi.")
        // China keeps Beijing time throughout, whatever tzdb says of Xinjiang.
        assertThat(asked("What time is it in China?")).isEqualTo("It is 5:57 AM on Tuesday in China.")
        assertThat(asked("What time is it in Hong Kong, China?")).isEqualTo("It is 5:57 AM on Tuesday in Hong Kong.")
        assertThat(asked("What time is it in Wellington?")).isEqualTo("It is 10:57 AM on Tuesday in Wellington.")
        assertThat(asked("What time is it in Wellington, New Zealand?"))
            .isEqualTo("It is 10:57 AM on Tuesday in Wellington.")
        assertThat(asked("What time is it in Doha?")).isEqualTo("It is 12:57 AM on Tuesday in Doha.")
        assertThat(asked("What time is it in LA?")).isEqualTo("It is 2:57 PM in LA.")
        assertThat(asked("What time is it in Vegas?")).isEqualTo("It is 2:57 PM in Vegas.")
        assertThat(asked("What time is it in DC?")).isEqualTo("It is 5:57 PM in DC.")
        assertThat(asked("What time is it in Macedonia?")).isEqualTo("It is 10:57 PM in Macedonia.")
        // Spain's Canaries and New Zealand's Chathams are on other times: asking is true.
        assertThat(asked("What time is it in Spain?"))
            .isEqualTo("Spain has more than one time zone. Ask me about a city there.")
        assertThat(asked("What time is it in New Zealand?"))
            .isEqualTo("New Zealand has more than one time zone. Ask me about a city there.")
        assertThat(asked("What time is it in Portugal?"))
            .isEqualTo("Portugal has more than one time zone. Ask me about a city there.")
        // US towns with no zone of their own, alone or in a state not all on one time, are
        // declined rather than given a guess.
        for (town in listOf(
            "Austin, Texas",
            "Orlando",
            "Orlando, Florida",
            "Nashville",
            "St. Louis",
            "Savannah, Georgia",
        )) {
            assertThat(asked("What time is it in $town?")).isEqualTo("I don't know the time zone for $town.")
        }
    }

    @Test
    fun `questions about no place at all are left to the model, not told of time zones`() {
        val questions = listOf(
            "What's the date in a fortnight?", "What's the date in a decade?", "What time is it in Mandarin?",
            "What time is it in Cantonese?", "What time is it in Farsi?", "What time is it in Filipino?",
            "What is the date in Bengali?", "What's the date in Islam?", "What is the date in Islamic?",
            "What day is it in Ramadan?", "Tell me the time in words.", "What time is it in the video?",
            "What time is it in the game?", "Tell me the time in Tokyo every morning.",
        )
        for (question in questions) assertThat(asked(question)).isNull()
        // A name the phone cannot tell from a place's is still told it has no time zone, which is true.
        assertThat(asked("What time is it in Minecraft?")).isEqualTo("I don't know the time zone for Minecraft.")
    }

    private companion object {
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

        /** The time-zone data this JVM has, which is the data the zones in these tests follow. */
        val TZDB: String = ZoneRulesProvider.getVersions("UTC").lastKey()

        /**
         * What ICU tells the phone about these countries' zones, cut down to the ones the
         * tests use, and spelled ICU's way (Asia/Calcutta, Asia/Katmandu, America/Cordoba).
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
            "AR" to listOf("America/Buenos_Aires", "America/Cordoba", "America/Mendoza"),
            "BR" to listOf("America/Sao_Paulo", "America/Manaus", "America/Noronha"),
            "CA" to listOf(
                "America/Toronto",
                "America/Vancouver",
                "America/Edmonton",
                "America/Winnipeg",
                "America/Halifax",
                "America/St_Johns",
                "America/Regina",
                "America/Inuvik",
            ),
            "ES" to listOf("Africa/Ceuta", "Atlantic/Canary", "Europe/Madrid"),
            "GB" to listOf("Europe/London"),
            "GE" to listOf("Asia/Tbilisi"),
            "IN" to listOf("Asia/Calcutta"),
            "JE" to listOf("Europe/Jersey"),
            "JP" to listOf("Asia/Tokyo"),
            "KP" to listOf("Asia/Pyongyang"),
            "KR" to listOf("Asia/Seoul"),
            "MA" to listOf("Africa/Casablanca"),
            "MK" to listOf("Europe/Skopje"),
            "MX" to listOf(
                "America/Mexico_City",
                "America/Merida",
                "America/Cancun",
                "America/Hermosillo",
                "America/Tijuana",
            ),
            "NF" to listOf("Pacific/Norfolk"),
            "NP" to listOf("Asia/Katmandu"),
            "NZ" to listOf("Pacific/Auckland", "Pacific/Chatham"),
            "PK" to listOf("Asia/Karachi"),
            "PT" to listOf("Atlantic/Azores", "Atlantic/Madeira", "Europe/Lisbon"),
            "RU" to listOf("Europe/Kaliningrad", "Europe/Moscow", "Asia/Vladivostok"),
            "SA" to listOf("Asia/Riyadh"),
            "TT" to listOf("America/Port_of_Spain"),
            "VE" to listOf("America/Caracas"),
            "WS" to listOf("Pacific/Apia"),
            "AS" to listOf("Pacific/Pago_Pago"),
            "ZA" to listOf("Africa/Johannesburg"),
        )

        val PLACES = Places(TZDB) { ZONES[it].orEmpty() }
    }
}
