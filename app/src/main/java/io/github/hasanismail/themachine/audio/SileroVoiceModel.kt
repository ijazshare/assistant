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

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Silero VAD v5.1.2 through sherpa-onnx's [Vad], which the app already carries for the voice.
 *
 * Only [Vad.compute] and [Vad.reset] are used. The rest of the class (acceptWaveform and its
 * queue) shares the model's memory with them and would corrupt it.
 *
 * sherpa-onnx ends the whole process, uncatchably, on a window that is not [VoiceCheckLayer.WINDOW]
 * samples, and a v4 model wants 512. So [open] is for the one file the registry names, after its
 * SHA-256 has been checked, and [probability] refuses any other length before the native call.
 *
 * [Vad] is not thread-safe. Captures use it from the recorder's thread one at a time, but a
 * capture that would not stop can overlap the next, and a dismissal can release it mid-capture,
 * so every call is serialised and a released model throws, which turns the check off, instead of
 * reaching freed memory.
 */
class SileroVoiceModel private constructor(private val vad: Vad) : VoiceModel {
    private var released = false

    @Synchronized
    override fun probability(window: FloatArray): Float {
        check(!released) { "voice model released" }
        require(window.size == VoiceCheckLayer.WINDOW) { "window of ${window.size} samples" }
        return vad.compute(window)
    }

    @Synchronized
    override fun reset() {
        check(!released) { "voice model released" }
        vad.reset()
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        vad.release()
    }

    companion object {
        /** Loads the model at [file]: the verified Silero v5.1.2 file, nothing else. */
        fun open(file: File): SileroVoiceModel {
            // Class defaults, but for the path. The segmentation fields are unused by compute()
            // and only have to validate.
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = file.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 5f,
                ),
                sampleRate = 16_000,
                numThreads = 1,
                provider = "cpu",
            )
            return SileroVoiceModel(Vad(null, config))
        }
    }
}
