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

/**
 * When to stop listening, tested without a microphone.
 *
 * These are the behaviours a user notices immediately: cutting them off mid-sentence,
 * or sitting there recording after they have finished.
 */
class EndpointerTest {

    @Test
    fun `frames during the lead-in are neither speech nor silence`() {
        // The overlay greets the user out loud while this is running, and the microphone
        // hears it. Those frames used to start an utterance that ended before the user
        // had said anything.
        val endpointer = Endpointer(leadInMillis = 200)
        val leadInFrames = (200 / Endpointer.DEFAULT_FRAME_MILLIS).toInt()
        repeat(leadInFrames) {
            assertThat(endpointer.accept(0.4f)).isEqualTo(FrameVerdict.Continue)
        }
        // And the room is measured only after it, so the greeting does not become the
        // noise floor either.
        repeat(Endpointer.CALIBRATION_FRAMES) { endpointer.accept(0.005f) }
        assertThat(endpointer.accept(0.4f)).isEqualTo(FrameVerdict.SpeechBegan)
    }

    private val quiet = 0.002f
    private val speech = 0.20f

    private fun feed(endpointer: Endpointer, level: Float, frames: Int): FrameVerdict {
        var last: FrameVerdict = FrameVerdict.Continue
        repeat(frames) {
            if (last is FrameVerdict.Stop) return last
            last = endpointer.accept(level)
        }
        return last
    }

    @Test
    fun `stays quiet through calibration`() {
        val endpointer = Endpointer()
        // The opening frames measure the room; nothing should be concluded from them.
        assertThat(feed(endpointer, quiet, frames = 10)).isEqualTo(FrameVerdict.Continue)
        assertThat(endpointer.speechStarted).isFalse()
    }

    @Test
    fun `detects speech once it rises above the room`() {
        val endpointer = Endpointer()
        feed(endpointer, quiet, frames = 10)
        assertThat(endpointer.accept(speech)).isEqualTo(FrameVerdict.SpeechBegan)
        assertThat(endpointer.speechStarted).isTrue()
    }

    @Test
    fun `stops after the trailing silence, not before`() {
        val endpointer = Endpointer(trailingSilenceMillis = 800)
        feed(endpointer, quiet, frames = 10)
        feed(endpointer, speech, frames = 25)

        // 800 ms at 20 ms per frame is 40 frames. One short must not end the sentence.
        assertThat(feed(endpointer, quiet, frames = 39)).isEqualTo(FrameVerdict.Continue)
        assertThat(endpointer.accept(quiet)).isEqualTo(FrameVerdict.Stop(StopReason.ENDPOINT))
    }

    @Test
    fun `a pause mid-sentence does not end the capture`() {
        val endpointer = Endpointer(trailingSilenceMillis = 800)
        feed(endpointer, quiet, frames = 10)
        feed(endpointer, speech, frames = 20)
        // Someone drawing breath: well short of the trailing threshold.
        feed(endpointer, quiet, frames = 20)
        feed(endpointer, speech, frames = 20)
        // Having resumed, the silence counter must have been reset.
        assertThat(feed(endpointer, quiet, frames = 39)).isEqualTo(FrameVerdict.Continue)
    }

    @Test
    fun `gives up when nothing is ever said`() {
        val endpointer = Endpointer(noSpeechGiveUpMillis = 3_000)
        // 3 s at 20 ms is 150 frames; a little past that it should stop waiting.
        val verdict = feed(endpointer, quiet, frames = 200)
        assertThat(verdict).isEqualTo(FrameVerdict.Stop(StopReason.NO_SPEECH))
    }

    @Test
    fun `caps a speaker who never stops`() {
        val endpointer = Endpointer(maxDurationMillis = 1_000)
        feed(endpointer, quiet, frames = 10)
        // 1 s at 20 ms is 50 frames of continuous speech.
        val verdict = feed(endpointer, speech, frames = 100)
        assertThat(verdict).isEqualTo(FrameVerdict.Stop(StopReason.MAX_DURATION))
    }

    @Test
    fun `a loud room raises the bar rather than triggering on itself`() {
        val endpointer = Endpointer()
        // Calibrate to a noisy environment — a car, a cafe.
        val roomNoise = 0.05f
        feed(endpointer, roomNoise, frames = 10)
        // The same level must now read as background, not as someone speaking.
        assertThat(endpointer.accept(roomNoise)).isEqualTo(FrameVerdict.Continue)
        assertThat(endpointer.speechStarted).isFalse()
        // Actual speech still has to clear it.
        assertThat(endpointer.accept(0.4f)).isEqualTo(FrameVerdict.SpeechBegan)
    }

    @Test
    fun `a silent room still detects quiet speech`() {
        val endpointer = Endpointer()
        // Near-perfect silence would make any multiple of the floor trigger on nothing,
        // which is why there is an absolute minimum as well.
        feed(endpointer, 0.0001f, frames = 10)
        assertThat(endpointer.accept(0.005f)).isEqualTo(FrameVerdict.Continue)
        assertThat(endpointer.accept(0.05f)).isEqualTo(FrameVerdict.SpeechBegan)
    }
}
