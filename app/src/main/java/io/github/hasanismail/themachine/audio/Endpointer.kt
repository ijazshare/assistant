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

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

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
 * Decides when the speaker has finished, from two levels per frame.
 *
 * Split out of the recorder so the decision can be reasoned about — and tested — without
 * a microphone. Energy rather than a neural VAD on purpose: this is push-to-talk, so the user
 * has already said they are about to speak. The only hard question is when they stopped, and a
 * second model to load and get wrong does not answer it better (one tried offline took a
 * television's voice for the user's).
 *
 * Each frame comes as its RMS above 400 Hz and above 1.5 kHz ([FrameMeter]). Every bar is
 * relative to the room or to the speaker. The bar this replaced, on the raw level (three times
 * the quietest calibration frame, and never under 0.012), sat inside the rumble of a car's
 * vent, which put frame after frame above it and kept the microphone open until the vent went
 * quiet or the 20 s cap came; and it sat above quiet speech, which it cut off.
 *
 *  - The room is a level that moves for the whole capture: up 6 dB/s while frames are louder
 *    than it, down 5 dB/s while they are quieter. When the user stops it is still near their
 *    voice, so what follows reads as quiet; noise that starts later is adopted within seconds
 *    instead of holding the capture open.
 *  - Speech begins on two frames in a row above twice the room, or on one above six times it.
 *  - It ends on the first of three things: the hangover (43 of the last 44 frames quiet), 2.6 s
 *    with nothing well above the room (noise whose jitter keeps the hangover open), or 2 s with
 *    nothing near the speaker's own level (a television or music that stays below them).
 *  - The 2.6 s rule also waits for 1 s of quiet above 1.5 kHz. A vent blowing on the phone can
 *    be as loud as the voice below that, but above it the vent is a steady hiss and the voice
 *    still shows: two frames in a row 1.4 times the band's quietest frame of the last 2 s. It
 *    never waits more than 4 s.
 */
class Endpointer(
    private val frameMillis: Long = DEFAULT_FRAME_MILLIS,
    trailingSilenceMillis: Long = DEFAULT_TRAILING_SILENCE_MILLIS,
    maxDurationMillis: Long = DEFAULT_MAX_DURATION_MILLIS,
    private val noSpeechGiveUpMillis: Long = DEFAULT_NO_SPEECH_MILLIS,
    leadInMillis: Long = 0,
) {
    // The per-frame rates (LEVEL_RISE, LEVEL_FALL, SMOOTHING) assume 20 ms frames, the only size
    // the app uses.
    private val quietNeeded = (trailingSilenceMillis / frameMillis).toInt().coerceAtLeast(1)
    private val window = quietNeeded + LOUD_ALLOWED
    private val maxFrames = (maxDurationMillis / frameMillis).toInt()
    private val noSpeechFrames = (noSpeechGiveUpMillis / frameMillis).toInt()
    private val leadInFrames = (leadInMillis / frameMillis).toInt()
    private val noStrongFrames = (NO_STRONG_MILLIS / frameMillis).toInt()
    private val noNearFrames = (NO_NEAR_MILLIS / frameMillis).toInt()
    private val highBandQuietFrames = (HIGH_BAND_QUIET_MILLIS / frameMillis).toInt()
    private val maxHoldFrames = (MAX_HOLD_MILLIS / frameMillis).toInt()

    private var framesSeen = 0

    // The last three frames: the level all of them reach (a voice holds it, a click does not),
    // and their RMS together.
    private var previousRms = 0f
    private var rmsBeforeThat = 0f
    private var sustained = 0f
    private var recent = 0f

    /** Frame power, a quarter this frame and three quarters what came before: how loud it is now. */
    private var power = 0f

    private val calibration = FloatArray(CALIBRATION_FRAMES)
    private var level = 0f
    private var floor = 0f
    private var previousAbove = false

    private var speakerLevel = 0f
    private val loud = BooleanArray(window)
    private var loudCount = 0
    private var utteranceFrames = 0
    private var framesWithoutStrong = 0
    private var framesWithoutNear = 0
    private var framesStrongRuleMet = 0

    private val highBandRecent = FloatArray((HIGH_BAND_FLOOR_MILLIS / frameMillis).toInt().coerceAtLeast(1))
    private var highBandFrames = 0
    private var previousHighBand = 0f
    private var highBandSustained = 0f
    private var highBandFloor = 0f
    private var lastHighBandActive = 0

    var speechStarted: Boolean = false
        private set

    /**
     * Feed one frame: [rms] above 400 Hz and [highBandRms] above 1.5 kHz ([FrameMeter.voiceBand]
     * and [FrameMeter.highBand]). Left at zero, the high band never holds a capture open.
     */
    fun accept(rms: Float, highBandRms: Float = 0f): FrameVerdict {
        framesSeen++
        remember(rms)

        // The microphone's own opening transient, not anything in the room. Counted as speech
        // it starts an utterance that is over before the user opens their mouth; counted as
        // silence it runs down the give-up clock before they could have begun.
        if (framesSeen <= leadInFrames) return FrameVerdict.Continue
        rememberHighBand(highBandRms)

        if (framesSeen <= leadInFrames + CALIBRATION_FRAMES) {
            calibrate(rms)
            return FrameVerdict.Continue
        }

        if (framesSeen >= maxFrames) return FrameVerdict.Stop(StopReason.MAX_DURATION)

        // Capped as well. A room that measures louder than ROOM_CAP is not a room, it is someone
        // talking into the microphone through calibration, and without a ceiling that reading
        // makes every human voice undetectable for the rest of the capture.
        val room = min(level, ROOM_CAP)
        return if (speechStarted) followUtterance(rms, room) else awaitSpeech(rms, room)
    }

    /** The last three frames, from the first frame on, and the smoothed power, from the end of the lead-in. */
    private fun remember(rms: Float) {
        if (framesSeen == leadInFrames + 1) {
            power = rms * rms
        } else if (framesSeen > leadInFrames) {
            power = SMOOTHING * rms * rms + (1f - SMOOTHING) * power
        }
        sustained = min(min(rms, previousRms), rmsBeforeThat)
        recent = sqrt((rms * rms + previousRms * previousRms + rmsBeforeThat * rmsBeforeThat) / RECENT_FRAMES)
        rmsBeforeThat = previousRms
        previousRms = rms
    }

    /**
     * The high band two frames at a time (one click or rumble spike is not a voice), and the
     * band's own quietest frame of the last two seconds, this one included.
     */
    private fun rememberHighBand(highBandRms: Float) {
        highBandSustained = min(highBandRms, previousHighBand)
        previousHighBand = highBandRms
        highBandRecent[highBandFrames % highBandRecent.size] = highBandRms
        highBandFrames++
        highBandFloor = highBandRecent[0]
        for (i in 1 until min(highBandFrames, highBandRecent.size)) {
            if (highBandRecent[i] < highBandFloor) highBandFloor = highBandRecent[i]
        }
    }

    /**
     * The opening frames measure the room, before anyone speaks — except that nothing stops the
     * user speaking during them, and push-to-talk invites exactly that. Two measures are kept.
     * The lower median is the room's level. The quietest frame is its floor: the quietest frame
     * of someone talking is still much closer to the room than their loudest, so when a voice
     * has poisoned the level, the floor still hears the speech begin (see [awaitSpeech]).
     */
    private fun calibrate(rms: Float) {
        val i = framesSeen - leadInFrames - 1
        calibration[i] = rms
        if (i == CALIBRATION_FRAMES - 1) {
            val sorted = calibration.copyOf().apply { sort() }
            level = sorted[(CALIBRATION_FRAMES - 1) / 2]
            floor = sorted[0]
        }
    }

    private fun awaitSpeech(rms: Float, room: Float): FrameVerdict {
        // The room can still prove quieter than calibration suggested, because that window caught
        // a cough, a door, or the first syllable of the command. The floor falls but never rises.
        if (rms < floor) floor = rms
        val byLevel = room * ONSET_LEVEL_MULT
        val byFloor = min(floor, ROOM_CAP) * ONSET_FLOOR_MULT
        val onset = max(min(byLevel, byFloor), MIN_ONSET_RMS)
        val above = rms > onset
        val floorOnly = above && !(rms > max(byLevel, MIN_ONSET_RMS))
        level = trackRoom(rms)
        // Only the floor heard it: the level was measured on the user's own voice. Pull it down
        // towards the floor, so the rest of the utterance is judged against the room, not them.
        if (floorOnly) level = max(min(level, floor * RESET_TO_FLOOR), level * RESET_MAX_DROP)
        val confirmed = (above && previousAbove) || rms > onset * STRONG_ONSET_MULT
        previousAbove = above
        if (confirmed) {
            speechStarted = true
            speakerLevel = sustained
            lastHighBandActive = framesSeen
            push(true)
            return FrameVerdict.SpeechBegan
        }
        // Nothing yet, and long enough that there probably will not be. Better to say so than
        // to record fifteen seconds of room tone and transcribe it. Counted from the end of the
        // lead-in, not from the start of the recording: charging the lead-in to the user once
        // left them about a second to begin, and every summon after the first came back "I did
        // not hear anything" without a word being transcribed.
        return if (framesSeen > leadInFrames + noSpeechFrames) {
            FrameVerdict.Stop(StopReason.NO_SPEECH)
        } else {
            FrameVerdict.Continue
        }
    }

    private fun followUtterance(rms: Float, room: Float): FrameVerdict {
        // The speaker's own level: it only rises, a twentieth of the way (in dB) per frame towards
        // what the voice sustains over three frames, so one shout does not become the bar.
        if (sustained > speakerLevel) {
            speakerLevel = if (speakerLevel > 0f) {
                speakerLevel * (sustained / speakerLevel).pow(SPEAKER_ATTACK)
            } else {
                sustained
            }
        }
        val isLoud = rms > max(room * LOUD_MULT, MIN_LOUD_RMS)
        level = trackRoom(rms)
        push(isLoud)
        val strong = recent > max(room * STRONG_MULT, MIN_STRONG_RMS)
        framesWithoutStrong = if (strong) 0 else framesWithoutStrong + 1
        val near = isLoud && sqrt(power) > speakerLevel * NEAR_SPEAKER
        framesWithoutNear = if (near) 0 else framesWithoutNear + 1
        if (highBandSustained > max(highBandFloor * HIGH_BAND_ACTIVE_MULT, MIN_HIGH_BAND_RMS)) {
            lastHighBandActive = framesSeen
        }
        val strongRuleMet = framesWithoutStrong >= noStrongFrames
        framesStrongRuleMet = if (strongRuleMet) framesStrongRuleMet + 1 else 0
        val heldByHighBand = framesSeen - lastHighBandActive < highBandQuietFrames &&
            framesStrongRuleMet <= maxHoldFrames
        val nothingStrong = strongRuleMet && !heldByHighBand
        val ended = hangoverOver() || nothingStrong || framesWithoutNear >= noNearFrames
        return if (ended) FrameVerdict.Stop(StopReason.ENDPOINT) else FrameVerdict.Continue
    }

    /** [quietNeeded] quiet frames among the last [window]: one click or breath is let through. */
    private fun hangoverOver(): Boolean = utteranceFrames >= window && loudCount <= LOUD_ALLOWED

    /** Up while frames are louder than the room, down while they are quieter; never gated on speech. */
    private fun trackRoom(rms: Float): Float = if (rms > level) level * LEVEL_RISE else level * LEVEL_FALL

    private fun push(isLoud: Boolean) {
        val slot = utteranceFrames % window
        if (loud[slot]) loudCount--
        loud[slot] = isLoud
        if (isLoud) loudCount++
        utteranceFrames++
    }

    companion object {
        const val DEFAULT_FRAME_MILLIS = 20L

        /**
         * Quiet that ends an utterance: 43 frames, among the last 44. One loud frame after the
         * last word (a click, a breath) does not start the count again.
         */
        const val DEFAULT_TRAILING_SILENCE_MILLIS = 860L
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

        // The room: +6 dB/s and -5 dB/s at 20 ms frames, and the loudest level believed as one.
        private const val LEVEL_RISE = 1.0139114f
        private const val LEVEL_FALL = 0.9885531f
        private const val ROOM_CAP = 0.08f

        // The onset, and the floor's rescue of a level measured on the user's voice.
        private const val ONSET_LEVEL_MULT = 2.0f
        private const val ONSET_FLOOR_MULT = 2.5f
        private const val MIN_ONSET_RMS = 0.004f
        private const val STRONG_ONSET_MULT = 3.0f
        private const val RESET_TO_FLOOR = 2.0f
        private const val RESET_MAX_DROP = 0.3f

        // The end: loud and strong frames against the room, near ones against the speaker.
        private const val LOUD_MULT = 1.25f
        private const val MIN_LOUD_RMS = 0.002f
        private const val LOUD_ALLOWED = 1
        private const val STRONG_MULT = 2.2f
        private const val MIN_STRONG_RMS = 0.004f
        private const val NO_STRONG_MILLIS = 2_600L
        private const val SMOOTHING = 0.25f
        private const val SPEAKER_ATTACK = 0.05f
        private const val NEAR_SPEAKER = 0.85f
        private const val NO_NEAR_MILLIS = 2_000L
        private const val RECENT_FRAMES = 3f

        // The high band's hold on the 2.6 s rule.
        private const val HIGH_BAND_FLOOR_MILLIS = 2_000L
        private const val HIGH_BAND_ACTIVE_MULT = 1.4f
        private const val MIN_HIGH_BAND_RMS = 0.001f
        private const val HIGH_BAND_QUIET_MILLIS = 1_000L
        private const val MAX_HOLD_MILLIS = 4_000L
    }
}
