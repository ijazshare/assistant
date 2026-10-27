/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.audio

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.ijazshare.assistant.models.ModelStorage
import java.io.File

/**
 * Silero VAD v5.1.2 through sherpa-onnx's [Vad], which the app already carries for the voice.
 *
 * Only [Vad.compute] and [Vad.reset] are used. The rest of the class (acceptWaveform and its
 * queue) shares the model's memory with them and would corrupt it.
 *
 * sherpa-onnx ends the whole process, uncatchably, on a window that is not [VoiceCheckLayer.WINDOW]
 * samples, and a v4 model wants 512. So [open] refuses every file but the pinned v5.1.2 one, by
 * size and SHA-256, whoever calls it, and [VoiceModel.probability] refuses any other window length
 * before the native call.
 *
 * The [Vad] has one memory, and it is not thread-safe. Captures use it from the recorder's thread
 * one at a time, but a capture that would not stop can overlap the next, and a dismissal can release
 * it mid-capture. So every call is serialised, a released model throws, which turns the check off,
 * instead of reaching freed memory, and each capture gets its own [newCapture] handle: only the
 * newest may use the memory, and an older one throws, so no window of one capture is ever heard by
 * another.
 */
class SileroVoiceModel internal constructor(private val engine: Engine) {
    /** What [Vad] is to this class, so the ownership rules can be tested without native code. */
    internal interface Engine {
        fun compute(window: FloatArray): Float

        fun reset()

        fun release()
    }

    private var released = false
    private var owner: VoiceModel? = null

    /**
     * A model for one capture. Its [VoiceModel.reset] takes the memory over from whichever capture
     * had it; from then on the older capture's [VoiceModel.probability] throws.
     */
    fun newCapture(): VoiceModel = Capture()

    @Synchronized
    fun release() {
        if (released) return
        released = true
        engine.release()
    }

    private inner class Capture : VoiceModel {
        override fun probability(window: FloatArray): Float = synchronized(this@SileroVoiceModel) {
            check(!released) { "voice model released" }
            check(owner === this) { "voice model taken over by a newer capture" }
            require(window.size == VoiceCheckLayer.WINDOW) { "window of ${window.size} samples" }
            engine.compute(window)
        }

        override fun reset() = synchronized(this@SileroVoiceModel) {
            check(!released) { "voice model released" }
            owner = this
            engine.reset()
        }
    }

    private class VadEngine(private val vad: Vad) : Engine {
        override fun compute(window: FloatArray) = vad.compute(window)

        override fun reset() = vad.reset()

        override fun release() = vad.release()
    }

    companion object {
        /** The one file this can run: Silero VAD v5.1.2, which takes 576-sample windows. */
        const val SHA256 = "2623a2953f6ff3d2c1e61740c6cdb7168133479b267dfef114a4a3cc5bdd788f"
        const val BYTES = 2_327_524L

        /**
         * Loads the model at [file]: the Silero v5.1.2 file, nothing else.
         * @throws IllegalArgumentException if the file is anything else
         */
        fun open(file: File): SileroVoiceModel {
            requirePinned(file, SHA256, BYTES)
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
            return SileroVoiceModel(VadEngine(Vad(null, config)))
        }

        /** About 2.3 MB, so the hash is a few milliseconds. */
        internal fun requirePinned(file: File, sha256: String, bytes: Long) {
            require(file.isFile && file.length() == bytes) { "not the pinned voice model: size" }
            val actual = ModelStorage(file.absoluteFile.parentFile!!).sha256(file)
            require(actual.equals(sha256, ignoreCase = true)) { "not the pinned voice model: hash" }
        }
    }
}
