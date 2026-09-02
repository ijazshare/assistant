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
    fun accept(rms: Float): FrameVerdict {
        framesSeen++

        // The opening frames establish what this room sounds like, before anyone speaks.
        if (framesSeen <= CALIBRATION_FRAMES) {
            noiseFloor = if (framesSeen == 1) {
                rms
            } else {
                noiseFloor * (1 - CALIBRATION_ALPHA) + rms * CALIBRATION_ALPHA
            }
            return FrameVerdict.Continue
        }

        if (framesSeen >= maxFrames) return FrameVerdict.Stop(StopReason.MAX_DURATION)

        val threshold = (noiseFloor * SPEECH_MULTIPLIER).coerceAtLeast(MIN_SPEECH_RMS)
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
            framesSeen > noSpeechFrames -> FrameVerdict.Stop(StopReason.NO_SPEECH)

            else -> FrameVerdict.Continue
        }
    }

    companion object {
        const val DEFAULT_FRAME_MILLIS = 20L
        const val DEFAULT_TRAILING_SILENCE_MILLIS = 800L
        const val DEFAULT_MAX_DURATION_MILLIS = 15_000L
        const val DEFAULT_NO_SPEECH_MILLIS = 3_000L

        private const val CALIBRATION_FRAMES = 10
        private const val CALIBRATION_ALPHA = 0.3f

        /** Speech has to be meaningfully above the room, not merely above it. */
        private const val SPEECH_MULTIPLIER = 3.0f

        /** A floor for silent rooms, where noiseFloor * multiplier is still near zero. */
        private const val MIN_SPEECH_RMS = 0.012f
    }
}
