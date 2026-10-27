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

/**
 * A neural voice detector, as [VoiceCheckLayer] uses one: how likely a window of audio is to hold
 * a human voice, with a memory of the windows before it.
 */
interface VoiceModel {
    /**
     * The probability, 0 to 1, that [window] holds a voice. Exactly [VoiceCheckLayer.WINDOW]
     * samples: 64 of the window before and 512 new. The model remembers what it has heard until
     * [reset]. May throw; the check is then off for the rest of that capture.
     */
    fun probability(window: FloatArray): Float

    /** Forgets everything heard: the next window is the first of a new capture. */
    fun reset()
}

/** What a capture's voice check did, for the log and the archive. */
data class VoiceCheckReport(
    /** Whether the check ran to the end of the capture: a model was present and it never threw. */
    val active: Boolean,
    /** The frame (from 1) on which it first heard a voice for certain; 0 if it never did. */
    val armedFrame: Int,
    /** What ended the capture: null if nothing did, or the caller stopped it. */
    val stoppedBy: VoiceCheckLayer.StoppedBy?,
) {
    val armed: Boolean get() = armedFrame > 0
}

/**
 * Ends a capture sooner than the energy [Endpointer] would, once a voice model has heard the
 * speaker stop. It can end a capture earlier, never later: the energy part runs first on every
 * frame, exactly as it would alone, and keeps every verdict it gives.
 *
 * Why it exists. Energy cannot tell the user's voice from a vent, a fan or birds. The energy
 * part waits out such noise (a vent blowing on the phone held the capture open for 13 s), and
 * a model that was trained to hear a voice can say that the user has stopped. It is one-way on
 * purpose: this model also takes a television's voice for the user, so it is never trusted to
 * keep a capture open, only to close one.
 *
 * The rule, on a probability for each 32 ms window of the audio above 300 Hz:
 *  - from the frame the energy part reports speech began, windows count;
 *  - it arms on 3 windows in a row above 0.9: a voice has been heard, not a click or a rumble;
 *  - once armed, 25 windows in a row (800 ms) at or below 0.2 end the capture.
 * The constants were fixed on a tuning set and then scored on others without change.
 *
 * With no model, or once it throws, this is the energy part alone: same verdicts, frame for frame.
 *
 * Windows. The model takes exactly [WINDOW] samples every time: 64 it has already seen and 512
 * new, so a window completes every 512 samples. A wrong length does not throw: the native library
 * ends the whole process. Nothing is fed during the microphone's opening [leadInFrames].
 *
 * One per capture, and per source opened: the filter and the model both start from silence.
 * Not thread-safe.
 *
 * @param energy the energy part, asked first on every frame with the levels it would get alone:
 *   `Endpointer(...)::accept`.
 * @param model the voice model, or null for none.
 */
class VoiceCheckLayer(
    private val energy: (rms: Float, highBandRms: Float) -> FrameVerdict,
    private val model: VoiceModel?,
    private val leadInFrames: Int = LEAD_IN_FRAMES,
) {
    /** Which part ended a capture. */
    enum class StoppedBy { ENERGY, VOICE }

    private var filter = HighPass.voiceCheck()
    private val window = FloatArray(WINDOW)
    private var filled = 0
    private var frames = 0
    private var armRun = 0
    private var silentRun = 0
    private var began = false
    private var disabled = model == null

    /** The frame (from 1) the check armed on; 0 if it has not. */
    var armedFrame = 0
        private set

    /** What ended the capture, once something has. */
    var stoppedBy: StoppedBy? = null
        private set

    /** Why the model was given up on, if it was. */
    var failure: Exception? = null
        private set

    /** Whether a model is present and has not failed. */
    val active: Boolean get() = !disabled

    init {
        reset()
    }

    /**
     * Back to the state of a microphone just opened, the model's memory included. The energy part
     * is the caller's: a new capture gets a new one.
     */
    fun reset() {
        filter = HighPass.voiceCheck()
        filled = 0
        frames = 0
        armRun = 0
        silentRun = 0
        began = false
        disabled = model == null
        armedFrame = 0
        stoppedBy = null
        failure = null
        if (model != null) {
            try {
                model.reset()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                disabled = true
                failure = e
            }
        }
    }

    /** What the check did so far. */
    fun report() = VoiceCheckReport(active, armedFrame, stoppedBy)

    /**
     * One frame: [rms] and [highBandRms] for the energy part, and the first [count] of [samples],
     * the same frame as recorded (int16 / 32767) for the model.
     */
    fun accept(rms: Float, highBandRms: Float, samples: FloatArray, count: Int): FrameVerdict {
        frames++
        val verdict = energy(rms, highBandRms)
        if (verdict == FrameVerdict.SpeechBegan) began = true
        val counting = verdict !is FrameVerdict.Stop && began
        var ends = false
        if (!disabled) {
            val feeding = frames > leadInFrames
            for (i in 0 until count) {
                val y = filter.process(samples[i])
                if (feeding) ends = feed(y, counting) || ends
                if (disabled) break
            }
        }
        if (verdict is FrameVerdict.Stop) {
            stoppedBy = StoppedBy.ENERGY
            return verdict
        }
        if (ends) {
            stoppedBy = StoppedBy.VOICE
            return FrameVerdict.Stop(StopReason.ENDPOINT)
        }
        return verdict
    }

    /** One filtered sample into the window; true if it completed one that says the speaker is done. */
    private fun feed(y: Float, counting: Boolean): Boolean {
        window[filled++] = y
        if (filled < WINDOW) return false
        val voice = model ?: return false
        val p = try {
            voice.probability(window)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            disabled = true
            failure = e
            return false
        } finally {
            // The last 64 samples are the context of the next window.
            window.copyInto(window, 0, SHIFT, WINDOW)
            filled = WINDOW - SHIFT
        }
        return counting && onWindow(p)
    }

    private fun onWindow(p: Float): Boolean {
        armRun = if (p > ARM_THRESHOLD) armRun + 1 else 0
        if (armRun >= ARM_WINDOWS && armedFrame == 0) armedFrame = frames
        silentRun = if (p > VOICE_THRESHOLD) 0 else silentRun + 1
        return armedFrame > 0 && silentRun >= SILENCE_WINDOWS
    }

    companion object {
        /** Samples per model call: 64 of context and 512 new. Anything else ends the process. */
        const val WINDOW = 576

        /** New samples per window: 32 ms. */
        const val SHIFT = 512

        /** The microphone's opening, which is filtered but never shown to the model. */
        val LEAD_IN_FRAMES = (Endpointer.MIC_SETTLE_MILLIS / Endpointer.DEFAULT_FRAME_MILLIS).toInt()

        /** A window above this is a voice for certain. */
        const val ARM_THRESHOLD = 0.9f
        const val ARM_WINDOWS = 3

        /** A window at or below this is not a voice. */
        const val VOICE_THRESHOLD = 0.2f

        /** 25 windows, 800 ms. */
        const val SILENCE_WINDOWS = 25
    }
}
