/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/**
 * The cues are synthesised rather than loaded from assets, which makes the waveform
 * itself ordinary testable code. These assert the properties that would otherwise
 * only be caught by ear on a device.
 */
class MachineSoundsTest {

    private val allCues = MachineSounds.Cue.entries

    @Test
    fun `every cue renders a non-empty waveform`() {
        for (cue in allCues) {
            assertThat(MachineSounds.render(cue).size).isGreaterThan(0)
        }
    }

    @Test
    fun `every cue is short enough to read as interface feedback`() {
        for (cue in allCues) {
            val millis = MachineSounds.render(cue).size * 1000L / MachineSounds.SAMPLE_RATE
            // Past roughly three-quarters of a second a cue stops feeling like an
            // acknowledgement and starts feeling like a notification sound.
            assertThat(millis).isIn(10L..750L)
        }
    }

    @Test
    fun `no cue starts or ends with a pop`() {
        for (cue in allCues) {
            val pcm = MachineSounds.render(cue)
            // A non-zero first or last sample is a step discontinuity, which the
            // speaker reproduces as a click. The attack ramp exists to prevent it.
            val headroom = Short.MAX_VALUE * 0.02
            assertThat(abs(pcm.first().toInt()).toDouble()).isLessThan(headroom)
            assertThat(abs(pcm.last().toInt()).toDouble()).isLessThan(headroom)
        }
    }

    @Test
    fun `no cue clips`() {
        for (cue in allCues) {
            val peak = MachineSounds.render(cue).maxOf { abs(it.toInt()) }
            // Hitting full scale would mean the mix is being clamped rather than
            // shaped, which sounds like distortion on louder speakers.
            assertThat(peak).isLessThan(Short.MAX_VALUE.toInt())
            // ...but a cue that never gets near full scale is inaudible in a pocket.
            assertThat(peak).isGreaterThan((Short.MAX_VALUE * 0.2).toInt())
        }
    }

    @Test
    fun `rendering is deterministic`() {
        for (cue in allCues) {
            assertThat(MachineSounds.render(cue)).isEqualTo(MachineSounds.render(cue))
        }
    }

    @Test
    fun `engage rises and disengage falls`() {
        // The pair has to be distinguishable without looking at the screen: capture
        // starting and capture ending are the two states a user most needs to feel.
        val engage = MachineSounds.render(MachineSounds.Cue.ENGAGE)
        val disengage = MachineSounds.render(MachineSounds.Cue.DISENGAGE)
        assertThat(zeroCrossingsInSecondHalf(engage))
            .isGreaterThan(zeroCrossingsInSecondHalf(disengage))
    }

    /** A cheap proxy for pitch: more zero crossings in a fixed window means higher. */
    private fun zeroCrossingsInSecondHalf(pcm: ShortArray): Int {
        var crossings = 0
        for (i in (pcm.size / 2 + 1) until pcm.size) {
            if ((pcm[i - 1] < 0) != (pcm[i] < 0)) crossings++
        }
        return crossings
    }
}
