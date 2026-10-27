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

import kotlin.math.sqrt

/**
 * Measures each frame of one capture three ways, from the same samples:
 *
 *  - [raw], as recorded: the level meter and the dead-source check use it;
 *  - [voiceBand], above 400 Hz ([HighPass.voiceBand]): what [Endpointer] judges speech on;
 *  - [highBand], above 1.5 kHz ([HighPass.highBand]): what keeps it listening through a vent.
 *
 * One per capture. The filters carry their state from frame to frame and start from silence
 * when the microphone opens, so nothing a previous capture heard rings into this one.
 */
class FrameMeter {
    private val voiceFilter = HighPass.voiceBand()
    private val highFilter = HighPass.highBand()

    /** RMS of the last frame as recorded. */
    var raw = 0f
        private set

    /** RMS of the last frame above 400 Hz. */
    var voiceBand = 0f
        private set

    /** RMS of the last frame above 1.5 kHz. */
    var highBand = 0f
        private set

    /**
     * Converts the first [read] samples of [buffer] to float, appends them to [into] unfiltered
     * (Whisper hears the audio as recorded), and measures them. The same samples go into
     * [frame], if given, for the voice check.
     */
    fun measure(buffer: ShortArray, read: Int, into: MutableList<Float>, frame: FloatArray? = null) {
        var rawSquares = 0.0
        var voiceSquares = 0.0
        var highSquares = 0.0
        for (i in 0 until read) {
            val sample = buffer[i] / Short.MAX_VALUE.toFloat()
            into.add(sample)
            if (frame != null) frame[i] = sample
            rawSquares += (sample * sample).toDouble()
            val voice = voiceFilter.process(sample)
            voiceSquares += (voice * voice).toDouble()
            val high = highFilter.process(sample)
            highSquares += (high * high).toDouble()
        }
        raw = sqrt(rawSquares / read).toFloat()
        voiceBand = sqrt(voiceSquares / read).toFloat()
        highBand = sqrt(highSquares / read).toFloat()
    }

    companion object {
        /** What [voiceBand] and [highBand] measure: each band's high-pass cutoff in Hz and its order. */
        const val FRONT_END = "hp400o4+hp1500o4"
    }
}
