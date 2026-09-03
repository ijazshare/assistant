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

import kotlin.math.min

/** What the endpointer concluded from one frame. */
sealed interface FrameVerdict {
    /** Still calibrating or still listening. */
    data object Continue : FrameVerdict

    /** Speech just began. */
    data object SpeechBegan : FrameVerdict

    /** Capture should stop, for this reason. */
    data class Stop(val reason: StopReason) : FrameVerdict
}

/**
 * Decides when the speaker has finished, from frame energy alone.
 *
 * Split out of the recorder so the decision can be reasoned about — and tested — without
 * a microphone. It is a small state machine: calibrate to the room, wait for something
 * clearly above it, then wait for that to stop.
 *
 * Energy rather than a neural VAD on purpose: this is push-to-talk, so the user has
 * already said they are about to speak. The only hard question is when they stopped,
 * and a second model to load and get wrong does not answer it better.
 */
class Endpointer(
    private val frameMillis: Long = DEFAULT_FRAME_MILLIS,
    trailingSilenceMillis: Long = DEFAULT_TRAILING_SILENCE_MILLIS,
    maxDurationMillis: Long = DEFAULT_MAX_DURATION_MILLIS,
    private val noSpeechGiveUpMillis: Long = DEFAULT_NO_SPEECH_MILLIS,
    leadInMillis: Long = 0,
) {
    private val framesOfSilenceToStop = (trailingSilenceMillis / frameMillis).toInt().coerceAtLeast(1)
    private val maxFrames = (maxDurationMillis / frameMillis).toInt()
    private val noSpeechFrames = (noSpeechGiveUpMillis / frameMillis).toInt()

    private var noiseFloor = 0f
    private var framesSeen = 0
    private var silentFrames = 0

    var speechStarted: Boolean = false
        private set

    /** Feed one frame's RMS. */
    private val leadInFrames = (leadInMillis / frameMillis).toInt()

    fun accept(rms: Float): FrameVerdict {
        framesSeen++

        // The assistant announces itself when it opens, and those words come out of the
        // speaker into this microphone. Frames during the announcement are not evidence
        // of anything: counted as speech they start an utterance that is over before the
        // user opens their mouth, and counted as silence they run down the give-up clock
        // while the user is still being greeted.
        if (framesSeen <= leadInFrames) return FrameVerdict.Continue

        // The opening frames establish what this room sounds like, before anyone speaks —
        // except that nothing stops the user speaking during them, and push-to-talk
        // invites exactly that. Averaged, a voice in this window becomes "the room", the
        // threshold lands three times above anything a person produces, and every frame
        // afterwards is judged silence: five seconds later the assistant reports that it
        // did not hear anything and throws away a perfectly good recording. The quietest
        // frame is taken instead of the average, because the quietest frame of someone
        // talking is still much closer to the room than their loudest.
        if (framesSeen <= leadInFrames + CALIBRATION_FRAMES) {
            noiseFloor = if (framesSeen == leadInFrames + 1) rms else min(noiseFloor, rms)
            return FrameVerdict.Continue
        }

        if (framesSeen >= maxFrames) return FrameVerdict.Stop(StopReason.MAX_DURATION)

        // The room can still prove quieter than the calibration window suggested, because
        // that window caught a cough, a door, or the first syllable of the command. The
        // floor is allowed to fall but never to rise, so a poisoned calibration corrects
        // itself at the first gap between words instead of lasting the whole session.
        if (!speechStarted && rms < noiseFloor) noiseFloor = rms

        // Capped as well as floored. A room that measures louder than MAX_NOISE_FLOOR is
        // not a room, it is someone speaking directly into the microphone, and without a
        // ceiling that reading makes every human voice undetectable for the rest of the
        // session. Being slightly eager in a genuinely loud place is the better failure:
        // it records and transcribes something, rather than insisting nothing was said.
        val room = min(noiseFloor, MAX_NOISE_FLOOR)
        val threshold = (room * SPEECH_MULTIPLIER).coerceAtLeast(MIN_SPEECH_RMS)
        val isSpeech = rms > threshold

        return when {
            isSpeech && !speechStarted -> {
                speechStarted = true
                silentFrames = 0
                FrameVerdict.SpeechBegan
            }

            isSpeech -> {
                silentFrames = 0
                FrameVerdict.Continue
            }

            speechStarted -> {
                silentFrames++
                if (silentFrames >= framesOfSilenceToStop) {
                    FrameVerdict.Stop(StopReason.ENDPOINT)
                } else {
                    FrameVerdict.Continue
                }
            }

            // Nothing yet, and long enough that there probably will not be. Better to
            // say so than to record fifteen seconds of room tone and transcribe it.
            // Counted from the end of the lead-in, not from the start of the recording.
            // The lead-in exists because the assistant is talking over itself for the
            // first moment; charging that time to the user left them about a second to
            // begin, and every summon after the first came back "I did not hear
            // anything" three seconds later without a word being transcribed.
            framesSeen > leadInFrames + noSpeechFrames ->
                FrameVerdict.Stop(StopReason.NO_SPEECH)

            else -> FrameVerdict.Continue
        }
    }

    companion object {
        const val DEFAULT_FRAME_MILLIS = 20L
        const val DEFAULT_TRAILING_SILENCE_MILLIS = 800L
        const val DEFAULT_MAX_DURATION_MILLIS = 20_000L

        /**
         * How long to wait for a first word, measured from when the assistant stops
         * announcing itself. Long enough to notice the overlay and draw breath.
         */
        const val DEFAULT_NO_SPEECH_MILLIS = 5_000L

        /**
         * Long enough to skip the microphone's own opening transient, and no longer.
         *
         * This was 1700 ms, sized to cover a greeting spoken out of the speaker. Nothing
         * is spoken before listening — the greeting is drawn, not said, and its per-word
         * ticks are already silenced while the microphone is open — so all that length
         * bought was 1.7 seconds in which the user's speech could not be detected at all.
         * A command shorter than the lead-in was inaudible by construction.
         *
         * Zero by default: settling belongs to whoever opens the microphone, not to
         * endpointing, and a test feeding frames directly should not have to know about it.
         */
        const val MIC_SETTLE_MILLIS = 300L

        internal const val CALIBRATION_FRAMES = 10

        /** Speech has to be meaningfully above the room, not merely above it. */
        private const val SPEECH_MULTIPLIER = 3.0f

        /** A floor for silent rooms, where noiseFloor * multiplier is still near zero. */
        private const val MIN_SPEECH_RMS = 0.012f

        /**
         * The loudest a measurement is allowed to be believed as room tone.
         *
         * Ordinary speech sits far above this; a calibration window that reads higher has
         * measured a voice, not a room.
         */
        private const val MAX_NOISE_FLOOR = 0.02f
    }
}
