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
        lengths.forEach { (said, minutes) ->
            assertThat(StatedTime.relativeMinutes("remind me $said to call Ali")).isEqualTo(minutes)
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
        assertThat(StatedTime.relativeMinutes("remind me in the car to call Ali")).isNull()
        assertThat(StatedTime.relativeMinutes("remind me in a bit")).isNull()
        assertThat(StatedTime.relativeMinutes("remind me in 3 days")).isNull()
        assertThat(StatedTime.relativeMinutes("remind me in 50 hours")).isNull()
        assertThat(StatedTime.relativeMinutes("remind me at 7 to call Ali")).isNull()
    }

    @Test
    fun `a specific time gets its hour and minute from the words`() {
        // The model's hour is only used to say which of two sevens; the minute is read.
        assertThat(spoken("remind me at 9:35 to call Ali", hour = 9, minute = 0))
            .isEqualTo(StatedTime.At(9, 35, null))
        assertThat(spoken("remind me at 9.35 to call Ali", hour = 9, minute = 0))
            .isEqualTo(StatedTime.At(9, 35, null))
        assertThat(spoken("remind me 9 35 am to call Ali", hour = 9, minute = 0))
            .isEqualTo(StatedTime.At(9, 35, null))
        assertThat(spoken("remind me at 9:35pm to call Ali", hour = 9, minute = 0))
            .isEqualTo(StatedTime.At(21, 35, null))
    }

    @Test
    fun `pm in any spelling, and o'clock`() {
        assertThat(spoken("remind me at 3 pm to call Ali", hour = 3, minute = 0))
            .isEqualTo(StatedTime.At(15, 0, null))
        assertThat(spoken("remind me at 3 p.m. to call Ali", hour = 3, minute = 0))
            .isEqualTo(StatedTime.At(15, 0, null))
        // The model's stray minute does not survive "3 pm".
        assertThat(spoken("alarm at 3 pm", hour = 15, minute = 3)).isEqualTo(StatedTime.At(15, 0, null))
        // But minutes said in words are left to the model.
        assertThat(spoken("alarm at half past 3 pm", hour = 15, minute = 30))
            .isEqualTo(StatedTime.At(15, 30, null))
        assertThat(TimeResolver.reconcileHour("remind me at 3 p.m.", 3)).isEqualTo(15)
    }

    @Test
    fun `noon and midnight are twelve and zero, o'clock`() {
        assertThat(spoken("remind me at noon to eat", hour = 3, minute = 15))
            .isEqualTo(StatedTime.At(12, 0, null))
        assertThat(spoken("remind me at midnight to sleep", hour = 12, minute = 0))
            .isEqualTo(StatedTime.At(0, 0, null))
        assertThat(spoken("wake me at noon", hour = null, minute = null))
            .isEqualTo(StatedTime.At(12, 0, null))
    }

    @Test
    fun `tomorrow at 7 is seven tomorrow`() {
        assertThat(spoken("remind me tomorrow at 7 to call Ali", hour = 3, minute = 0))
            .isEqualTo(StatedTime.At(7, 0, tomorrow = true))
        assertThat(spoken("remind me tomorrow at 7 to call Ali", hour = 7, minute = 0))
            .isEqualTo(StatedTime.At(7, 0, tomorrow = true))
        // Which seven is the model's to say when nothing does.
        assertThat(spoken("remind me at 7 to call Ali", hour = 19, minute = 0))
            .isEqualTo(StatedTime.At(19, 0, null))
        // Nor is "at ten to six" the hour ten.
        assertThat(TimeResolver.reconcileHour("remind me at ten to six", 5)).isEqualTo(5)
    }

    @Test
    fun `no time words leave the model alone`() {
        assertThat(spoken("remind me to buy milk", hour = null, minute = null)).isNull()
        assertThat(spoken("remind me to buy milk", hour = 7, minute = 30))
            .isEqualTo(StatedTime.At(7, 30, null))
        assertThat(spoken("wake me at 18:00", hour = 18, minute = 0)).isEqualTo(StatedTime.At(18, 0, null))
    }
}
