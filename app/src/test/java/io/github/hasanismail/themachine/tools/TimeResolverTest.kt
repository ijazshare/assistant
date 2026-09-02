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

/**
 * These cases are the ones the model actually got wrong on device before the arithmetic
 * moved out of the prompt, so they are regression guards rather than illustrations.
 */
class TimeResolverTest {

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
    fun `converts an afternoon hour the way it was spoken`() {
        // "half past six in the evening" — the case that came back as hour 2.
        assertThat(TimeResolver.to24Hour(6, "pm")).isEqualTo(18)
        assertThat(TimeResolver.to24Hour(7, "am")).isEqualTo(7)
        assertThat(TimeResolver.to24Hour(11, "PM")).isEqualTo(23)
    }

    @Test
    fun `handles the two midnights`() {
        assertThat(TimeResolver.to24Hour(12, "am")).isEqualTo(0)
        assertThat(TimeResolver.to24Hour(12, "pm")).isEqualTo(12)
    }

    @Test
    fun `leaves an unambiguous hour alone`() {
        // A model that converted on its own must not be converted a second time.
        assertThat(TimeResolver.to24Hour(18, "pm")).isEqualTo(18)
        assertThat(TimeResolver.to24Hour(9, null)).isEqualTo(9)
        assertThat(TimeResolver.to24Hour(0, null)).isEqualTo(0)
    }

    @Test
    fun `rejects an hour that is not on the clock`() {
        assertThat(TimeResolver.to24Hour(null, "am")).isNull()
        assertThat(TimeResolver.to24Hour(24, null)).isNull()
        assertThat(TimeResolver.to24Hour(-1, null)).isNull()
    }

    @Test
    fun `defaults minutes to the hour and rejects the impossible`() {
        assertThat(TimeResolver.minuteOf(null)).isEqualTo(0)
        assertThat(TimeResolver.minuteOf(30)).isEqualTo(30)
        assertThat(TimeResolver.minuteOf(60)).isNull()
    }
}
