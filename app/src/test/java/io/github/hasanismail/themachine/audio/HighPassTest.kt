/*
 * Assistant — an offline voice assistant for Android.
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The two bands the endpointer listens in, and the voice check's own filter. */
class HighPassTest {

    /** The filter's gain at [hz]: the RMS ratio over the second half of a one-second tone. */
    private fun gain(filter: HighPass, hz: Double): Double {
        val n = 16_000
        var inSquares = 0.0
        var outSquares = 0.0
        for (i in 0 until n) {
            val x = (0.1 * sin(2 * PI * hz * i / n)).toFloat()
            val y = filter.process(x)
            if (i >= n / 2) {
                inSquares += (x * x).toDouble()
                outSquares += (y * y).toDouble()
            }
        }
        return sqrt(outSquares / inSquares)
    }

    @Test
    fun `the voice band takes out a vent's rumble and keeps the voice`() {
        assertThat(gain(HighPass.voiceBand(), 100.0)).isLessThan(0.01) // -48 dB
        assertThat(gain(HighPass.voiceBand(), 400.0)).isWithin(0.01).of(sqrt(0.5)) // -3 dB at the cutoff
        assertThat(gain(HighPass.voiceBand(), 1_000.0)).isWithin(0.01).of(1.0)
    }

    @Test
    fun `the voice check's filter is the 2nd order 300 Hz Butterworth, apart from the endpointer's`() {
        assertThat(gain(HighPass.voiceCheck(), 100.0)).isWithin(0.003).of(10.0.pow(-19.1 / 20)) // -19.1 dB
        assertThat(gain(HighPass.voiceCheck(), 300.0)).isWithin(0.005).of(sqrt(0.5))
        assertThat(gain(HighPass.voiceCheck(), 1_000.0)).isWithin(0.01).of(1.0)
        // Gentler than the 4th-order 400 Hz band: that one is -48 dB at 100 Hz.
        assertThat(gain(HighPass.voiceCheck(), 100.0)).isGreaterThan(10 * gain(HighPass.voiceBand(), 100.0))
    }

    @Test
    fun `the voice check's coefficients are the RBJ high-pass at 300 Hz`() {
        // Independent of the constants in HighPass: the cookbook formula, run per sample.
        val w0 = 2 * PI * 300.0 / 16_000.0
        val alpha = sin(w0) / (2 * sqrt(0.5))
        val a0 = 1 + alpha
        val b0 = (1 + cos(w0)) / 2 / a0
        val b1 = -(1 + cos(w0)) / a0
        val a1 = -2 * cos(w0) / a0
        val a2 = (1 - alpha) / a0
        var s1 = 0.0
        var s2 = 0.0
        val filter = HighPass.voiceCheck()
        val random = java.util.Random(4)
        repeat(3_200) {
            val x = (random.nextGaussian() * 0.05).toFloat()
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b0 * x - a2 * y
            assertThat(filter.process(x).toDouble()).isWithin(1e-6).of(y)
        }
    }

    @Test
    fun `the high band keeps only what a vent barely reaches`() {
        assertThat(gain(HighPass.highBand(), 400.0)).isLessThan(0.01) // -47 dB
        assertThat(gain(HighPass.highBand(), 1_500.0)).isWithin(0.01).of(sqrt(0.5))
        assertThat(gain(HighPass.highBand(), 3_000.0)).isWithin(0.01).of(1.0)
    }
}
