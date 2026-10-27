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
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** The two bands the endpointer listens in. */
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
    fun `the high band keeps only what a vent barely reaches`() {
        assertThat(gain(HighPass.highBand(), 400.0)).isLessThan(0.01) // -47 dB
        assertThat(gain(HighPass.highBand(), 1_500.0)).isWithin(0.01).of(sqrt(0.5))
        assertThat(gain(HighPass.highBand(), 3_000.0)).isWithin(0.01).of(1.0)
    }
}
