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

/**
 * The endpointer's ears: a Butterworth high-pass for 16 kHz audio, in one of two bands (4th order),
 * and a third, 2nd-order one for the voice check.
 *
 *  - [voiceBand] (400 Hz) is what the endpointer judges speech on. A car's vent or a fan is mostly
 *    energy below 300 Hz at speaking volume; the voice a person can follow lives above it.
 *  - [highBand] (1500 Hz) is where a vent blowing on the phone is only a steady hiss while the
 *    voice still stands out. The endpointer uses it for one thing: not giving up on the speaker
 *    while they are still audible up there.
 *
 * Two biquad sections each (bilinear transform with the cutoff pre-warped, Q 0.5412 then 1.3066),
 * transposed direct form II in Double. The state carries from sample to sample, across frames,
 * and starts at zero: one filter per capture. The coefficients are written out rather than
 * computed so that every device gets the same bits.
 */
class HighPass private constructor(private val sections: Array<DoubleArray>) {
    private val s1 = DoubleArray(sections.size)
    private val s2 = DoubleArray(sections.size)

    /** One sample in, one sample out. */
    fun process(sample: Float): Float {
        var v = sample.toDouble()
        for (k in sections.indices) {
            val c = sections[k]
            val out = c[B0] * v + s1[k]
            s1[k] = c[B1] * v - c[A1] * out + s2[k]
            s2[k] = c[B2] * v - c[A2] * out
            v = out
        }
        return v.toFloat()
    }

    companion object {
        /** 400 Hz: the level [Endpointer] judges speech on. -24 dB at 200 Hz, -48 dB at 100 Hz. */
        fun voiceBand(): HighPass = HighPass(VOICE_BAND)

        /** 1500 Hz: the level that keeps [Endpointer] listening through a vent's rumble. -47 dB at 400 Hz. */
        fun highBand(): HighPass = HighPass(HIGH_BAND)

        /**
         * 300 Hz, second order: the signal [VoiceCheckLayer] hands to its voice model, the one it
         * was measured on. Its own filter, not [voiceBand]: the model has not been tried on that one.
         * -19 dB at 100 Hz, -3 dB at 300 Hz.
         */
        fun voiceCheck(): HighPass = HighPass(VOICE_CHECK)

        // Each section is (b0, b1, b2, a1, a2), with a0 = 1.
        private const val B0 = 0
        private const val B1 = 1
        private const val B2 = 2
        private const val A1 = 3
        private const val A2 = 4

        private val VOICE_CHECK = arrayOf(
            doubleArrayOf(
                0.92006615842916795,
                -1.8401323168583359,
                0.92006615842916795,
                -1.8337326589246479,
                0.8465319747920238,
            ),
        )
        private val VOICE_BAND = arrayOf(
            doubleArrayOf(
                0.8683451917361831,
                -1.7366903834723662,
                0.8683451917361831,
                -1.7259333950369409,
                0.7474473719077911,
            ),
            doubleArrayOf(
                0.9377083729319824,
                -1.8754167458639648,
                0.9377083729319824,
                -1.8638004920752351,
                0.8870329996526947,
            ),
        )
        private val HIGH_BAND = arrayOf(
            doubleArrayOf(
                0.6051324447717696,
                -1.2102648895435393,
                0.6051324447717696,
                -1.0988972699656414,
                0.3216325091214371,
            ),
            doubleArrayOf(
                0.7551782322472279,
                -1.5103564644944558,
                0.7551782322472279,
                -1.371374655125205,
                0.6493382738637062,
            ),
        )
    }
}
