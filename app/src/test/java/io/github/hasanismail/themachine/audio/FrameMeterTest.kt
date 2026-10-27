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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What VoiceRecorder measures in each frame, tested without a microphone. */
class FrameMeterTest {

    private val frame = 320

    /** [frames] frames of a tone at [hz], as 16-bit samples at 16 kHz. */
    private fun tone(hz: Double, frames: Int, amplitude: Double = 0.3) = ShortArray(frames * frame) {
        (amplitude * Short.MAX_VALUE * sin(2 * PI * hz * it / 16_000.0)).roundToInt().toShort()
    }

    private fun frameOf(samples: ShortArray, k: Int) = samples.copyOfRange(k * frame, (k + 1) * frame)

    /** One meter fed every frame of [samples], holding the last frame's levels. */
    private fun measured(samples: ShortArray): FrameMeter {
        val meter = FrameMeter()
        for (k in 0 until samples.size / frame) meter.measure(frameOf(samples, k), frame, ArrayList())
        return meter
    }

    @Test
    fun `the raw level is the recording's own RMS, as the level meter has always shown it`() {
        val samples = tone(440.0, 1)
        var squares = 0.0
        for (s in samples) {
            val x = s / Short.MAX_VALUE.toFloat()
            squares += (x * x).toDouble()
        }
        assertThat(measured(samples).raw).isEqualTo(sqrt(squares / frame).toFloat())
    }

    @Test
    fun `the samples handed on for transcription are the recording, unfiltered`() {
        val samples = tone(100.0, 2)
        val collected = ArrayList<Float>()
        val meter = FrameMeter()
        meter.measure(frameOf(samples, 0), frame, collected)
        meter.measure(frameOf(samples, 1), frame, collected)
        assertThat(collected).containsExactlyElementsIn(samples.map { it / Short.MAX_VALUE.toFloat() }).inOrder()
    }

    @Test
    fun `the frame handed to the voice check is the recording, unfiltered, and only what was read`() {
        val samples = tone(100.0, 1)
        val voiceCheckFrame = FloatArray(frame) { Float.NaN }
        FrameMeter().measure(samples, frame - 20, ArrayList(), voiceCheckFrame)
        for (i in 0 until frame - 20) assertThat(voiceCheckFrame[i]).isEqualTo(samples[i] / Short.MAX_VALUE.toFloat())
        assertThat(voiceCheckFrame[frame - 20].isNaN()).isTrue()
    }

    @Test
    fun `a vent's rumble shows on the meter but barely reaches the endpointer`() {
        // 100 Hz at speaking volume, after the filters have settled.
        val rumble = measured(tone(100.0, 10))
        assertThat(rumble.voiceBand).isLessThan(0.01f * rumble.raw)
        assertThat(rumble.highBand).isLessThan(0.01f * rumble.raw)
    }

    @Test
    fun `a voice reaches the voice band whole and the high band by its upper part`() {
        val voice = measured(tone(1_000.0, 10))
        assertThat(voice.voiceBand).isWithin(0.02f * voice.raw).of(voice.raw)
        assertThat(voice.highBand).isLessThan(0.25f * voice.raw) // -15 dB at 1 kHz
        val sibilant = measured(tone(3_000.0, 10))
        assertThat(sibilant.highBand).isWithin(0.02f * sibilant.raw).of(sibilant.raw)
    }

    @Test
    fun `the filters carry from one frame to the next`() {
        // Each frame's levels are those of one filter run over the whole capture, not of a filter
        // started afresh at every frame boundary.
        val samples = tone(150.0, 6)
        val meter = FrameMeter()
        val voiceBand = HighPass.voiceBand()
        val highBand = HighPass.highBand()
        for (k in 0 until 6) {
            meter.measure(frameOf(samples, k), frame, ArrayList())
            var voiceSquares = 0.0
            var highSquares = 0.0
            for (i in k * frame until (k + 1) * frame) {
                val x = samples[i] / Short.MAX_VALUE.toFloat()
                val v = voiceBand.process(x)
                val h = highBand.process(x)
                voiceSquares += (v * v).toDouble()
                highSquares += (h * h).toDouble()
            }
            assertThat(meter.voiceBand).isEqualTo(sqrt(voiceSquares / frame).toFloat())
            assertThat(meter.highBand).isEqualTo(sqrt(highSquares / frame).toFloat())
        }
    }

    @Test
    fun `each capture's filters start from silence`() {
        // A tone cut off at the end of a frame leaves both filters ringing. Within a capture the
        // next frame carries that, as it should; VoiceRecorder gives every source it opens a new
        // meter, and a new meter hears none of what came before.
        val meter = FrameMeter()
        meter.measure(tone(1_000.0, 1), frame, ArrayList())
        val silence = ShortArray(frame)
        meter.measure(silence, frame, ArrayList())
        assertThat(meter.voiceBand).isGreaterThan(0f)
        assertThat(meter.highBand).isGreaterThan(0f)

        val next = FrameMeter()
        next.measure(silence, frame, ArrayList())
        assertThat(next.voiceBand).isEqualTo(0f)
        assertThat(next.highBand).isEqualTo(0f)
    }
}
