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
import kotlin.math.sqrt

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

    @Test
    fun `hears someone who starts talking the instant the overlay opens`() {
        // The reported bug, and the reason it looked like the assistant went deaf after
        // working once. Push-to-talk invites you to speak immediately, so the frames that
        // measure "the room" are full of the user's voice. Averaged, they set a noise
        // floor at speaking volume, the threshold lands three times higher than any human
        // gets, and every later frame is judged silence. Five seconds later it announces
        // that it did not hear anything, having discarded a perfectly good recording.
        val endpointer = Endpointer(leadInMillis = Endpointer.MIC_SETTLE_MILLIS)
        val leadIn = (Endpointer.MIC_SETTLE_MILLIS / Endpointer.DEFAULT_FRAME_MILLIS).toInt()

        // Talking from the very first frame, straight through the lead-in and the
        // calibration window.
        repeat(leadIn + Endpointer.CALIBRATION_FRAMES) { endpointer.accept(speech) }

        // Still talking. This must register.
        val verdict = feed(endpointer, speech, frames = 20)
        assertThat(verdict).isNotEqualTo(FrameVerdict.Stop(StopReason.NO_SPEECH))
        assertThat(endpointer.speechStarted).isTrue()
    }

    @Test
    fun `does not give up on someone who talked through calibration`() {
        // The same fault seen from the other end: the whole utterance is spoken, then the
        // room goes quiet, and the endpointer should close on the trailing silence rather
        // than report that nothing was ever said.
        val endpointer = Endpointer(leadInMillis = Endpointer.MIC_SETTLE_MILLIS)
        val leadIn = (Endpointer.MIC_SETTLE_MILLIS / Endpointer.DEFAULT_FRAME_MILLIS).toInt()
        repeat(leadIn) { endpointer.accept(speech) }

        feed(endpointer, speech, frames = 60)
        val verdict = feed(endpointer, quiet, frames = 60)
        assertThat(verdict).isEqualTo(FrameVerdict.Stop(StopReason.ENDPOINT))
    }

    @Test
    fun `catches a command shorter than the settling window`() {
        // "Timer ten minutes" is over in about a second. With a 1700 ms lead-in the whole
        // utterance fell inside the window where speech cannot be detected, so it was
        // recorded, never noticed, and thrown away as silence.
        val endpointer = Endpointer(leadInMillis = Endpointer.MIC_SETTLE_MILLIS)
        val spokenFrames = (1_000 / Endpointer.DEFAULT_FRAME_MILLIS).toInt()
        feed(endpointer, speech, frames = spokenFrames)
        assertThat(endpointer.speechStarted).isTrue()
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

    // The tests below run the endpointer as VoiceSession does: the microphone's lead-in, and the
    // default trailing quiet of 43 frames among the last 44.

    private val settled = (Endpointer.MIC_SETTLE_MILLIS / Endpointer.DEFAULT_FRAME_MILLIS).toInt() +
        Endpointer.CALIBRATION_FRAMES

    /** Speech as frame levels: syllables of 120 ms at [loud], with 80 ms dips to [dip] between them. */
    private fun talking(frames: Int, loud: Float = 0.1f, dip: Float = 0.01f) =
        FloatArray(frames) { if (it % 10 < 6) loud else dip }

    private fun room(frames: Int, level: Float = quiet) = FloatArray(frames) { level }

    /** Where speech began and the capture stopped (frames, from 1), and why; nulls if it never did. */
    private class Outcome(val began: Int?, val stopped: Int?, val reason: StopReason?)

    private fun listen(vararg parts: FloatArray): Outcome {
        val endpointer = Endpointer(leadInMillis = Endpointer.MIC_SETTLE_MILLIS)
        var began: Int? = null
        var frame = 0
        for (rms in parts.flatMap { it.asList() }) {
            frame++
            when (val verdict = endpointer.accept(rms)) {
                FrameVerdict.SpeechBegan -> began = frame
                is FrameVerdict.Stop -> return Outcome(began, frame, verdict.reason)
                FrameVerdict.Continue -> Unit
            }
        }
        return Outcome(began, null, null)
    }

    @Test
    fun `a rumble that starts as the user stops does not keep the microphone open`() {
        // A fan or an engine coming up just as the user finishes, steady at 0.03. Every frame of
        // it is above the 0.012 the old endpointer took for speech, so it never heard an end and
        // ran to the 20 s cap. The rumble is well below the speaker: 2 s with nothing near their
        // level ends the capture, counted from the last frame still near them, which the smoothed
        // level carries a few frames past the last word.
        val lead = room(settled + 5)
        val speech = talking(75)
        val lastWord = lead.size + speech.size

        val outcome = listen(lead, speech, room(900, level = 0.03f))

        assertThat(outcome.reason).isEqualTo(StopReason.ENDPOINT)
        assertThat(outcome.stopped).isGreaterThan(lastWord)
        assertThat(outcome.stopped).isAtMost(lastWord + 110)
    }

    @Test
    fun `noise that only goes away much later does not hold the capture until it does`() {
        // A car's vent: there from the moment the microphone opens, jittering from frame to frame,
        // and switched off 15 s after the user stops. Its peaks are four times its quietest frames.
        // Judged against three times the quietest calibration frame, as the old endpointer judged
        // it, they were speech until the vent went off. Now the capture ends within the slowest
        // rule's 2.6 s of the last word: 130 frames with nothing well above the room, counted from
        // a three-frame window that still reaches the last word two frames on.
        val vent = floatArrayOf(0.015f, 0.025f, 0.06f)
        val ventAt = { frame: Int -> vent[frame % vent.size] }
        val before = FloatArray(settled + 10) { ventAt(it) }
        val speech = talking(75, loud = 0.15f, dip = 0.03f)
        val voice = FloatArray(speech.size) { k ->
            val noise = ventAt(before.size + k)
            sqrt(speech[k] * speech[k] + noise * noise)
        }
        val lastWord = before.size + voice.size
        val after = FloatArray(750) { ventAt(lastWord + it) }

        val outcome = listen(before, voice, after, room(200))

        assertThat(outcome.reason).isEqualTo(StopReason.ENDPOINT)
        assertThat(outcome.stopped).isGreaterThan(lastWord)
        assertThat(outcome.stopped).isAtMost(lastWord + 132)
    }

    @Test
    fun `someone who talks through calibration is heard and let go on the trailing quiet`() {
        // Talking from the instant the microphone opens, straight through the lead-in and the
        // calibration window, so the room's level is measured on their voice. Its quietest frame,
        // a dip between syllables, is still close enough to the room to hear the speech begin the
        // moment calibration ends, and the capture ends on the usual trailing quiet.
        val speech = talking(80)
        val lastSyllable = speech.indexOfLast { it == 0.1f } + 1

        val outcome = listen(speech, room(200))

        assertThat(outcome.began).isEqualTo(settled + 1)
        assertThat(outcome.reason).isEqualTo(StopReason.ENDPOINT)
        assertThat(outcome.stopped).isEqualTo(lastSyllable + 43)
    }

    @Test
    fun `a pause shorter than the trailing quiet does not end the sentence`() {
        // Someone stopping to think for 0.84 s mid-command: 42 quiet frames, one short of the 43
        // that end an utterance. The capture follows the rest of the sentence and ends 43 frames
        // after it. A frame more of pause, and the pause is the end.
        val lead = room(settled + 5)
        val first = talking(50)
        val rest = talking(50)

        val held = listen(lead, first, room(42), rest, room(100))
        assertThat(held.reason).isEqualTo(StopReason.ENDPOINT)
        assertThat(held.stopped).isEqualTo(lead.size + first.size + 42 + rest.size + 43)

        val ended = listen(lead, first, room(43), rest, room(100))
        assertThat(ended.stopped).isEqualTo(lead.size + first.size + 43)
    }
}
