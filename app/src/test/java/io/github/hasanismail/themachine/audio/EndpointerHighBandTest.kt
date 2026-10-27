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
import java.util.Random

/**
 * The car, tested without a car: a vent blowing on the phone is as loud as the voice in the band
 * the endpointer judges speech on, but only a steady hiss above 1.5 kHz, where the voice still
 * shows. Synthetic frame levels only.
 */
class EndpointerHighBandTest {

    // Voice band: the vent's jitter keeps the hangover open, and no frame is 2.2 times the room.
    private val vent = floatArrayOf(0.025f, 0.030f, 0.045f)
    private val hiss = 0.004f

    // 200 ms of voice above 1.5 kHz: 80 ms of syllable, then the vent's hiss until the next one.
    private val syllable = floatArrayOf(0.009f, 0.009f, 0.009f, 0.009f, hiss, hiss, hiss, hiss, hiss, hiss)

    private val leadIn = (Endpointer.MIC_SETTLE_MILLIS / Endpointer.DEFAULT_FRAME_MILLIS).toInt()
    private val before = leadIn + Endpointer.CALIBRATION_FRAMES + 21

    /**
     * Both levels of a capture in the car: the vent, one loud first syllable, [speechFrames] of
     * talking, then [tail] frames of the vent alone, with one-frame clicks in the high band at the
     * [clicks] frames of the tail.
     */
    private fun carCapture(
        speechFrames: Int,
        tail: Int = 900,
        clicks: IntArray = intArrayOf(),
    ): Pair<FloatArray, FloatArray> {
        val n = before + 1 + speechFrames + tail
        val voice = FloatArray(n)
        val high = FloatArray(n)
        for (k in 0 until before) {
            voice[k] = vent[k % vent.size]
            high[k] = hiss
        }
        voice[before] = 0.2f
        high[before] = 0.012f
        for (k in 0 until speechFrames + tail) {
            voice[before + 1 + k] = vent[k % vent.size]
            high[before + 1 + k] = if (k < speechFrames) syllable[k % syllable.size] else hiss
        }
        for (c in clicks) high[before + 1 + speechFrames + c] = 0.012f
        return voice to high
    }

    /** The frame (from 1) the capture stopped on and why, or null if it never stopped. */
    private fun stop(voice: FloatArray, high: FloatArray?): Pair<Int, StopReason>? {
        val endpointer = Endpointer(leadInMillis = Endpointer.MIC_SETTLE_MILLIS)
        for (k in voice.indices) {
            val v = if (high == null) endpointer.accept(voice[k]) else endpointer.accept(voice[k], high[k])
            if (v is FrameVerdict.Stop) return (k + 1) to v.reason
        }
        return null
    }

    @Test
    fun `a vent as loud as the voice does not cut the user off while they are still audible`() {
        // Three seconds of talking with the vent full on. The voice band alone gives up 2.6 s
        // after the first syllable and loses the end of the sentence.
        val speech = 150
        val lastSpeechFrame = before + 1 + speech
        val (voice, high) = carCapture(speech)

        val (frame, reason) = stop(voice, high)!!
        assertThat(reason).isEqualTo(StopReason.ENDPOINT)
        assertThat(frame).isGreaterThan(lastSpeechFrame)
        // And it still lets go promptly once they stop: within 1.5 s.
        assertThat(frame).isAtMost(lastSpeechFrame + 75)

        // Without the high band, the same capture is cut off mid-sentence.
        val (cut, _) = stop(voice, null)!!
        assertThat(cut).isLessThan(lastSpeechFrame)
    }

    @Test
    fun `one click in the high band after the user stops does not keep the microphone open`() {
        val (voice, high) = carCapture(150)
        val (clickedVoice, clicked) = carCapture(150, clicks = intArrayOf(20))
        assertThat(stop(clickedVoice, clicked)).isEqualTo(stop(voice, high))
    }

    @Test
    fun `two frames in a row in the high band count as the voice`() {
        val (voice, high) = carCapture(150)
        val (heardVoice, heard) = carCapture(150, clicks = intArrayOf(20, 21))
        assertThat(stop(heardVoice, heard)!!.first).isGreaterThan(stop(voice, high)!!.first)
    }

    @Test
    fun `the high band never holds the capture more than four seconds past the voice band's give-up`() {
        // A radio or a passenger keeps the high band busy for ever; the capture must still end,
        // and well before the 20 s cap. The voice band gives up 132 frames after the onset frame.
        val (voice, high) = carCapture(speechFrames = 2_000, tail = 0)
        val onset = before + 1
        val (frame, reason) = stop(voice, high)!!
        assertThat(reason).isEqualTo(StopReason.ENDPOINT)
        assertThat(frame).isEqualTo(onset + 132 + 200)
    }

    @Test
    fun `in a quiet room the high band changes nothing`() {
        val voice = FloatArray(150) { k -> if (k in 25 until 50) 0.2f else 0.002f }
        val high = FloatArray(150) { k -> if (k in 25 until 50) 0.05f else 0.0005f }
        assertThat(stop(voice, high)).isEqualTo(stop(voice, null))
    }

    @Test
    fun `a steady hiss in the high band does not hold the capture open`() {
        // Only the voice's ups and downs above 1.5 kHz count, not how loud the band is.
        val (voice, _) = carCapture(150)
        val high = FloatArray(voice.size) { hiss }
        assertThat(stop(voice, high)).isEqualTo(stop(voice, null))
    }

    @Test
    fun `left at zero the high band never holds the capture open`() {
        // accept(rms) is the endpointer without the hold: what EndpointerTest exercises.
        val random = Random(26)
        repeat(20) {
            val voice = FloatArray(1_000) { (0.002 + 0.1 * random.nextDouble() * random.nextDouble()).toFloat() }
            assertThat(stop(voice, FloatArray(voice.size))).isEqualTo(stop(voice, null))
        }
    }
}
