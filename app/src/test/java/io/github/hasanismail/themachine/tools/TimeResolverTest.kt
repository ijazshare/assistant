/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalTime

/**
 * These cases are the ones the model actually got wrong on device before the arithmetic
 * moved out of the prompt, so they are regression guards rather than illustrations.
 */
class TimeResolverTest {

    @Test
    fun `flags a time that only echoes the current clock`() {
        val now = LocalTime.of(23, 51)
        // The exact bug: "create a note about this" came back as hour 23, minute 51 at 23:51.
        assertThat(TimeResolver.echoesNow(23, 51, now)).isTrue()
        // A real, different time is not an echo.
        assertThat(TimeResolver.echoesNow(7, 0, now)).isFalse()
        assertThat(TimeResolver.echoesNow(23, 50, now)).isFalse()
        // No time at all is not an echo either.
        assertThat(TimeResolver.echoesNow(null, 0, now)).isFalse()
        assertThat(TimeResolver.echoesNow(23, null, now)).isFalse()
    }

    @Test
    fun `sums the parts of a duration`() {
        assertThat(TimeResolver.totalSeconds(null, 10, null)).isEqualTo(600)
        assertThat(TimeResolver.totalSeconds(1, 30, null)).isEqualTo(5400)
        assertThat(TimeResolver.totalSeconds(null, null, 45)).isEqualTo(45)
        assertThat(TimeResolver.totalSeconds(2, 0, 0)).isEqualTo(7200)
    }

    @Test
    fun `rejects a duration that is empty, zero or absurd`() {
        assertThat(TimeResolver.totalSeconds(null, null, null)).isNull()
        assertThat(TimeResolver.totalSeconds(0, 0, 0)).isNull()
        assertThat(TimeResolver.totalSeconds(null, -5, null)).isNull()
        assertThat(TimeResolver.totalSeconds(48, null, null)).isNull()
    }

    @Test
    fun `accepts every hour on a 24-hour clock`() {
        assertThat(TimeResolver.hourOf(0)).isEqualTo(0)
        assertThat(TimeResolver.hourOf(7)).isEqualTo(7)
        // "half past six in the evening" — the case that came back as 16, and then as 12
        // when the schema tried to take the conversion away from the model.
        assertThat(TimeResolver.hourOf(18)).isEqualTo(18)
        assertThat(TimeResolver.hourOf(23)).isEqualTo(23)
    }

    @Test
    fun `rejects an hour that is not on the clock`() {
        assertThat(TimeResolver.hourOf(null)).isNull()
        assertThat(TimeResolver.hourOf(24)).isNull()
        assertThat(TimeResolver.hourOf(-1)).isNull()
    }

    @Test
    fun `an evening hour the model got wrong is corrected from the words`() {
        // The exact failure: the model answered 16, the words say six and evening.
        assertThat(TimeResolver.reconcileHour("wake me at half past six in the evening", 16)).isEqualTo(18)
        assertThat(TimeResolver.reconcileHour("remind me to call Ali at 6pm", 6)).isEqualTo(18)
        assertThat(TimeResolver.reconcileHour("quarter past nine tonight", 9)).isEqualTo(21)
        assertThat(TimeResolver.reconcileHour("half past ten at night", 22)).isEqualTo(22)
    }

    @Test
    fun `a morning hour stays a morning hour`() {
        assertThat(TimeResolver.reconcileHour("set an alarm for 7 am", 7)).isEqualTo(7)
        assertThat(TimeResolver.reconcileHour("wake me at eight in the morning", 20)).isEqualTo(8)
        assertThat(TimeResolver.reconcileHour("alarm at 12 am", 12)).isEqualTo(0)
    }

    @Test
    fun `noon and midnight are not shifted past themselves`() {
        assertThat(TimeResolver.reconcileHour("alarm at 12 pm", 12)).isEqualTo(12)
        assertThat(TimeResolver.reconcileHour("wake me at noon", 12)).isEqualTo(12)
    }

    @Test
    fun `without a cue the model is left alone`() {
        // "Alarm at seven" genuinely does not say which seven.
        assertThat(TimeResolver.reconcileHour("set an alarm for 7", 7)).isEqualTo(7)
        assertThat(TimeResolver.reconcileHour("wake me at 18:00", 18)).isEqualTo(18)
        assertThat(TimeResolver.reconcileHour("remind me to buy milk", null)).isNull()
    }

    @Test
    fun `minutes are not mistaken for the hour`() {
        assertThat(TimeResolver.reconcileHour("alarm for 6:30 pm", 6)).isEqualTo(18)
    }

    @Test
    fun `defaults minutes to the hour and rejects the impossible`() {
        assertThat(TimeResolver.minuteOf(null)).isEqualTo(0)
        assertThat(TimeResolver.minuteOf(30)).isEqualTo(30)
        assertThat(TimeResolver.minuteOf(60)).isNull()
    }
}
