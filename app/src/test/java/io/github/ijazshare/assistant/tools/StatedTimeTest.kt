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
import java.time.LocalDateTime

/** The times a transcript states outright, and that code and not the model works out. */
class StatedTimeTest {

    // 09:04 is the minute of the bug: "remind me in 30 minutes" came back as 3:00 tomorrow.
    private val nineOhFour = LocalDateTime.of(2026, 10, 27, 9, 4, 10)

    private fun spoken(transcript: String, hour: Int? = 3, minute: Int? = 0, now: LocalDateTime = nineOhFour) =
        StatedTime.of(transcript, hour, minute, now)

    @Test
    fun `in thirty minutes is now plus thirty, whatever the model said`() {
        // The exact failure: hour 3, minute 0, tomorrow.
        val time = spoken("remind me in 30 minutes to print the certificates")
        assertThat(time).isEqualTo(StatedTime.At(9, 34, tomorrow = false))
    }

    @Test
    fun `relative lengths in digits and words`() {
        val lengths = mapOf(
            "in 30 minutes" to 30,
            "in five minutes" to 5,
            "in 1 minute" to 1,
            "in an hour" to 60,
            "in 2 hours" to 120,
            "in half an hour" to 30,
            "in an hour and a half" to 90,
            "in 2 and a half hours" to 150,
            "in 1 hour and 15 minutes" to 75,
            "in 90 min" to 90,
            "in twenty five minutes" to 25,
            "in forty-five minutes" to 45,
            "in 1.5 hours" to 90,
            "in a minute" to 1,
            "in one hour and thirty minutes" to 90,
        )
        val now = LocalDateTime.of(2026, 10, 27, 9, 4, 0)
        lengths.forEach { (said, minutes) ->
            val due = now.plusMinutes(minutes.toLong())
            assertThat(
                spoken("remind me $said to call Ali", now = now),
            ).isEqualTo(StatedTime.At(due.hour, due.minute, false))
        }
    }

    @Test
    fun `a relative time becomes the clock time and the day`() {
        val now = LocalDateTime.of(2026, 10, 27, 9, 4, 0)
        assertThat(spoken("wake me in 2 hours", now = now)).isEqualTo(StatedTime.At(11, 4, false))
        assertThat(spoken("in an hour and a half remind me to stretch", now = now))
            .isEqualTo(StatedTime.At(10, 34, false))
        assertThat(spoken("remind me in 1 minute to check the oven", now = now))
            .isEqualTo(StatedTime.At(9, 5, false))
    }

    @Test
    fun `a relative time rounds to the nearest minute`() {
        // 09:04:50 plus a minute is 09:05:50: the 09:06 alarm is ten seconds late, 09:05 fifty early.
        val late = LocalDateTime.of(2026, 10, 27, 9, 4, 50)
        assertThat(spoken("remind me in 1 minute", now = late)).isEqualTo(StatedTime.At(9, 6, false))
    }

    @Test
    fun `a relative time across midnight is tomorrow`() {
        val late = LocalDateTime.of(2026, 10, 27, 23, 50, 0)
        assertThat(spoken("remind me in 30 minutes to lock up", now = late))
            .isEqualTo(StatedTime.At(0, 20, tomorrow = true))
        // The model had said tomorrow for the wrong reason, and the answer is today.
        assertThat(spoken("remind me in 30 minutes to lock up", now = nineOhFour)?.tomorrow).isFalse()
    }

    @Test
    fun `in is only a duration when a length follows it`() {
        listOf("in the car to call Ali", "in a bit", "in 3 days", "at work to call Ali").forEach {
            assertThat(TimeExpressions.of("remind me $it")).isEmpty()
        }
        // A length over a day is a time expression, but not one code will set.
        assertThat(spoken("remind me in 50 hours", hour = 7)).isNull()
    }

    @Test
    fun `a specific time gets its hour and minute from the words`() {
        // The model's hour is only used to say which of two sevens; the minute is read.
        assertThat(spoken("remind me at 9:35 to call Ali", hour = 9, minute = 0))
            .isEqualTo(StatedTime.At(9, 35, false))
        assertThat(spoken("remind me at 9.35 to call Ali", hour = 9, minute = 0))
            .isEqualTo(StatedTime.At(9, 35, false))
        assertThat(spoken("remind me 9 35 am to call Ali", hour = 9, minute = 0))
            .isEqualTo(StatedTime.At(9, 35, false))
        assertThat(spoken("remind me at 9:35pm to call Ali", hour = 9, minute = 0))
            .isEqualTo(StatedTime.At(21, 35, false))
    }

    @Test
    fun `pm in any spelling, and o'clock`() {
        assertThat(spoken("remind me at 3 pm to call Ali", hour = 3, minute = 0))
            .isEqualTo(StatedTime.At(15, 0, false))
        assertThat(spoken("remind me at 3 p.m. to call Ali", hour = 3, minute = 0))
            .isEqualTo(StatedTime.At(15, 0, false))
        // The model's stray minute does not survive "3 pm".
        assertThat(spoken("alarm at 3 pm", hour = 15, minute = 3)).isEqualTo(StatedTime.At(15, 0, false))
        // But minutes said in words are left to the model.
        assertThat(spoken("alarm at half past 3 pm", hour = 15, minute = 30))
            .isEqualTo(StatedTime.At(15, 30, false))
    }

    @Test
    fun `the hour is the one time said, not the first small number`() {
        assertThat(spoken("Remind me to take 2 pills at 3 p.m.", hour = 15, minute = 0))
            .isEqualTo(StatedTime.At(15, 0, false))
        assertThat(spoken("Remind me to call 2 people at 5 pm", hour = 17, minute = 0))
            .isEqualTo(StatedTime.At(17, 0, false))
        assertThat(spoken("remind me to feed 3 cats at ten to six pm", hour = 17, minute = 50))
            .isEqualTo(StatedTime.At(17, 50, false))
    }

    @Test
    fun `a range of numbers is not a clock time`() {
        // "2 to 3" with no at, for or am/pm is a range: no time is stated, and the model's hour stands.
        assertThat(spoken("Remind me in 2 to 3 hours", hour = 16, minute = 0)).isNull()
        assertThat(TimeResolver.reconcileHour("Remind me in 2 to 3 hours", 16)).isEqualTo(16)
        assertThat(spoken("Remind me in 5 to 10 minutes", hour = 14, minute = 10)).isNull()
        assertThat(TimeResolver.reconcileHour("Remind me in 5 to 10 minutes", 14)).isEqualTo(14)
        assertThat(spoken("Remind me to buy 2 to 3 apples tomorrow", hour = 9, minute = 0)).isNull()
        assertThat(TimeResolver.reconcileHour("Remind me to buy 2 to 3 apples tomorrow", 9)).isEqualTo(9)
        // With its am or pm, or after at, it is a time again.
        assertThat(spoken("wake me ten to six am", hour = 5, minute = 50)).isEqualTo(StatedTime.At(5, 50, false))
        assertThat(spoken("remind me at ten to six", hour = 17, minute = 50)).isEqualTo(StatedTime.At(17, 50, false))
    }

    @Test
    fun `half 7 pm is half past, so its minutes stay the model's`() {
        assertThat(spoken("Remind me at half 7 pm", hour = 19, minute = 30)).isEqualTo(StatedTime.At(19, 30, false))
        assertThat(spoken("Remind me at quarter 8 pm", hour = 20, minute = 15)).isEqualTo(StatedTime.At(20, 15, false))
    }

    @Test
    fun `with two times p m is read as main read it, which is not at all`() {
        // Two times: the model's hour stands, as on main, and "p.m." does not tip it to the 9.
        assertThat(spoken("Remind me at 9 to call Ali at 5 p.m.", hour = 17, minute = 0)).isNull()
        assertThat(TimeResolver.reconcileHour("Remind me at 9 to call Ali at 5 p.m.", 17)).isEqualTo(17)
    }

    @Test
    fun `noon and midnight are twelve and zero, o'clock`() {
        assertThat(spoken("remind me at noon to eat", hour = 3, minute = 15))
            .isEqualTo(StatedTime.At(12, 0, false))
        assertThat(spoken("remind me at midnight to sleep", hour = 12, minute = 0))
            .isEqualTo(StatedTime.At(0, 0, false))
        assertThat(spoken("wake me at noon", hour = null, minute = null))
            .isEqualTo(StatedTime.At(12, 0, false))
    }

    @Test
    fun `tomorrow at 7 is seven tomorrow`() {
        assertThat(spoken("remind me tomorrow at 7 to call Ali", hour = 3, minute = 0))
            .isEqualTo(StatedTime.At(7, 0, tomorrow = true))
        assertThat(spoken("remind me tomorrow at 7 to call Ali", hour = 7, minute = 0))
            .isEqualTo(StatedTime.At(7, 0, tomorrow = true))
        // Which seven is the model's to say when nothing does.
        assertThat(spoken("remind me at 7 to call Ali", hour = 19, minute = 0))
            .isEqualTo(StatedTime.At(19, 0, false))
        // Nor is "at ten to six" the hour ten.
        assertThat(TimeResolver.reconcileHour("remind me at ten to six", 5)).isEqualTo(5)
    }

    @Test
    fun `no time words leave the model alone`() {
        assertThat(spoken("remind me to buy milk", hour = null, minute = null)).isNull()
        assertThat(spoken("remind me to buy milk", hour = 7, minute = 30)).isNull()
        // 18:00 is no clock time to read: the model's 18:00 stands.
        assertThat(spoken("wake me at 18:00", hour = 18, minute = 0)).isNull()
    }

    @Test
    fun `minutes said in words before pm are kept for the model`() {
        assertThat(spoken("set an alarm for seven ten pm", hour = 19, minute = 10))
            .isEqualTo(StatedTime.At(19, 10, false))
        assertThat(spoken("set an alarm for seven oh five pm", hour = 19, minute = 5))
            .isEqualTo(StatedTime.At(19, 5, false))
        assertThat(spoken("set an alarm for 7 5 pm", hour = 19, minute = 5)).isEqualTo(StatedTime.At(19, 5, false))
        // A lone hour is still o'clock.
        assertThat(spoken("set an alarm for ten pm", hour = 22, minute = 7)).isEqualTo(StatedTime.At(22, 0, false))
    }

    @Test
    fun `a price or date is not the minutes of the time`() {
        assertThat(spoken("remind me at 3 pm to pay the 12.50 bill", hour = 15, minute = 0))
            .isEqualTo(StatedTime.At(15, 0, false))
        assertThat(spoken("remind me at 6 pm on 10.11 to call", hour = 18, minute = 0))
            .isEqualTo(StatedTime.At(18, 0, false))
        assertThat(spoken("remind me at 3 pm to pay the 3.50 bill", hour = 15, minute = 0))
            .isEqualTo(StatedTime.At(15, 0, false))
        assertThat(spoken("remind me at 3 pm to pay the 4:20 bill", hour = 15, minute = 0))
            .isEqualTo(StatedTime.At(15, 0, false))
    }

    @Test
    fun `the day after tomorrow is not tomorrow`() {
        assertThat(spoken("remind me the day after tomorrow at 9 am", hour = 9, minute = 0)?.tomorrow).isFalse()
    }

    @Test
    fun `a time said with no day is not tomorrow, whatever the model said`() {
        // Said at 12:24 with the model's tomorrow: the reminder went to 4:15 the next day.
        assertThat(spoken("remind me at 4.15 pm to work something", hour = 16, minute = 15))
            .isEqualTo(StatedTime.At(16, 15, tomorrow = false))
        assertThat(spoken("remind me tonight at 9", hour = 21, minute = 0))
            .isEqualTo(StatedTime.At(21, 0, tomorrow = false))
    }

    @Test
    fun `another day named leaves the day to the model`() {
        // Said on a Sunday, Monday is tomorrow, and only the model was told the date.
        assertThat(spoken("remind me on Monday at 9 am", hour = 9, minute = 0))
            .isEqualTo(StatedTime.At(9, 0, tomorrow = null))
        assertThat(spoken("remind me on the 28th at 9 am", hour = 9, minute = 0))
            .isEqualTo(StatedTime.At(9, 0, tomorrow = null))
    }

    @Test
    fun `a day past tomorrow is recognised so it can be declined`() {
        assertThat(StatedTime.beyondTomorrow("remind me the day after tomorrow at 9 am")).isTrue()
        assertThat(StatedTime.beyondTomorrow("remind me tomorrow at 9 am")).isFalse()
        assertThat(StatedTime.beyondTomorrow("remind me at 9 after the meeting tomorrow")).isFalse()
    }

    @Test
    fun `twelve noon and twelve midnight are one time each`() {
        assertThat(spoken("set an alarm for twelve noon", hour = 0)).isEqualTo(StatedTime.At(12, 0, false))
        assertThat(spoken("set an alarm for 12 noon", hour = 0)).isEqualTo(StatedTime.At(12, 0, false))
        assertThat(spoken("set an alarm for twelve midnight", hour = 12)).isEqualTo(StatedTime.At(0, 0, false))
    }

    @Test
    fun `more than one time is left to the model`() {
        listOf(
            "Set an alarm for 6 tomorrow, I need to be at work at 8",
            "Remind me tomorrow at 9 to ask Ali to call me at 5",
            "Remind me tomorrow at 10 to have lunch with Ali at noon",
            "remind me at 9 to put in 2 hours of overtime",
            "remind me to leave for my 3 pm appointment in 30 minutes",
            "remind me at 9 am to book a table for 12 noon",
            "remind me at 11 am to submit the form before noon",
            "remind me at 6 to look at 2 flats",
            "remind me in half an hour to catch the 5 pm train",
        ).forEach { assertThat(spoken(it, hour = 9, minute = 0)).isNull() }
    }

    @Test
    fun `a length for how long is not a time`() {
        assertThat(spoken("remind me at 5 pm to study for 2 hours", hour = 17, minute = 0))
            .isEqualTo(StatedTime.At(17, 0, false))
    }

    @Test
    fun `a reminder or an alarm for a length is that long from now`() {
        // The model's 21:00, said at 21:07, became a reminder for tomorrow at 9 PM.
        assertThat(spoken("reminder for five minutes", hour = 21, minute = 0)).isEqualTo(StatedTime.At(9, 9, false))
        assertThat(spoken("Set a reminder for 5 minutes", hour = 21, minute = 0)).isEqualTo(StatedTime.At(9, 9, false))
        assertThat(spoken("set an alarm for 10 minutes")).isEqualTo(StatedTime.At(9, 14, false))
        assertThat(spoken("set a reminder for me for 5 minutes")).isEqualTo(StatedTime.At(9, 9, false))
        // "For" heard as "of", as it was at 21:29.
        assertThat(
            spoken("Set a reminder of five minutes", hour = 21, minute = 29),
        ).isEqualTo(StatedTime.At(9, 9, false))
        assertThat(TimeExpressions.of("remind me to take a break of 5 minutes")).isEmpty()
        assertThat(TimeExpressions.of("remind me for 5 minutes")).hasSize(1)
        // After a task it is still how long: "to study for 2 hours" has no time of its own.
        assertThat(TimeExpressions.of("remind me to study for 2 hours")).isEmpty()
        assertThat(TimeExpressions.of("remind me to turn off the alarm for 10 minutes")).isEmpty()
    }

    @Test
    fun `a task of nothing but the asking and the time is untitled`() {
        assertThat(StatedTime.isUntitled("five minutes")).isTrue()
        assertThat(StatedTime.isUntitled("set a reminder")).isTrue()
        assertThat(StatedTime.isUntitled("Set a reminder for 5 minutes")).isTrue()
        assertThat(StatedTime.isUntitled("set a reminder half past three")).isTrue()
        assertThat(StatedTime.isUntitled("a reminder at midnight")).isTrue()
        assertThat(StatedTime.isUntitled("tomorrow morning")).isTrue()
        assertThat(StatedTime.isUntitled("set the timer")).isFalse()
        assertThat(StatedTime.isUntitled("take a nap")).isFalse()
        assertThat(StatedTime.isUntitled("call Mom at 5")).isFalse()
    }

    @Test
    fun `one time with extra words is still one time`() {
        assertThat(
            spoken("remind me at 10 pm to lock up", hour = 22, minute = 0),
        ).isEqualTo(StatedTime.At(22, 0, false))
        assertThat(spoken("set an alarm for 7 o'clock", hour = 7, minute = 0)).isEqualTo(StatedTime.At(7, 0, false))
        assertThat(spoken("remind me tomorrow morning at 9 to log in", hour = 9, minute = 0))
            .isEqualTo(StatedTime.At(9, 0, true))
        assertThat(TimeExpressions.of("remind me at half past three pm")).hasSize(1)
        assertThat(TimeExpressions.of("remind me at ten to six")).hasSize(1)
    }
}
