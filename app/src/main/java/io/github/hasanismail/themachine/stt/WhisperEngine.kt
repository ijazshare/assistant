/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.stt

import android.content.Context
import android.util.Log
import io.github.hasanismail.themachine.nativebridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A transcript plus how long it took to produce. */
data class Transcription(val text: String, val durationMillis: Long, val audioMillis: Long) {
    /** Below 1.0 means faster than real time. */
    val realTimeFactor: Float
        get() = if (audioMillis > 0) durationMillis.toFloat() / audioMillis else 0f
}

/**
 * Speech to text, on the device.
 *
 * The context is loaded once and kept for the life of a session rather than per
 * utterance: loading tiny.en takes long enough that doing it per command would eat
 * most of the latency budget on its own.
 *
 * Not thread-safe by design — whisper_full mutates the context, so calls are
 * serialised by the single session that owns the engine.
 */
class WhisperEngine(private val context: Context) {

    @Volatile
    private var handle: Long = 0

    val isLoaded: Boolean get() = handle != 0L

    /**
     * Loads a GGML model. Returns false if the file is missing or unreadable, which is
     * the normal case before the user has downloaded a model.
     */
    suspend fun load(modelFile: File): Boolean = withContext(Dispatchers.Default) {
        if (!NativeBridge.load(context)) return@withContext false
        if (!modelFile.isFile) {
            Log.w(TAG, "whisper model missing: ${modelFile.absolutePath}")
            return@withContext false
        }
        unload()
        val loaded = WhisperNative.nativeLoad(modelFile.absolutePath)
        handle = loaded
        loaded != 0L
    }

    /**
     * Transcribes captured audio. [samples] must be 16 kHz mono in [-1, 1] — the rate
     * Whisper was trained on; anything else silently produces nonsense rather than an
     * error.
     */
    suspend fun transcribe(samples: FloatArray): Transcription = withContext(Dispatchers.Default) {
        val current = handle
        if (current == 0L || samples.isEmpty()) {
            return@withContext Transcription("", 0, 0)
        }
        val audioMillis = samples.size * MILLIS_PER_SECOND / SAMPLE_RATE
        val startedAt = System.nanoTime()
        val text = synchronized(this@WhisperEngine) {
            if (handle == 0L) "" else WhisperNative.nativeTranscribe(handle, samples, threadCount())
        }
        val elapsed = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        Transcription(text.trim(), elapsed, audioMillis.toLong())
    }

    /**
     * Transcription runs under this monitor and re-reads the handle inside it, so unload
     * waits for a pass in flight rather than freeing the context under it.
     */
    @Synchronized
    fun unload() {
        if (handle != 0L) {
            WhisperNative.nativeFree(handle)
            handle = 0
        }
    }

    /**
     * Leaves a couple of cores for the UI and the audio thread. Using every core makes
     * the transcription marginally faster and the interface visibly worse.
     */
    private fun threadCount(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, MAX_THREADS)

    companion object {
        private const val TAG = "TheMachine"

        /** Whisper is trained at 16 kHz; this is not a tunable. */
        const val SAMPLE_RATE = 16_000

        private const val MILLIS_PER_SECOND = 1000
        private const val NANOS_PER_MILLI = 1_000_000
        private const val MAX_THREADS = 6
    }
}
