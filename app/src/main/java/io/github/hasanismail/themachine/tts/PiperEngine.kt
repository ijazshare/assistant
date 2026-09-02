/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The assistant's voice: Piper's en_US-amy-medium, run offline through sherpa-onnx.
 *
 * Synthesis and playback are deliberately one call. A reply is a single short sentence
 * that has to finish before the overlay can go away, so there is nothing to gain from
 * handing the caller a buffer and a second thing to remember to do with it.
 */
class PiperEngine {

    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null

    val isLoaded: Boolean get() = tts != null

    /**
     * Loads the voice from an unpacked model directory.
     *
     * The layout is located rather than assumed: the tarball unpacks to a directory
     * named after itself, so the files sit one level down, and a future voice packaged
     * flat should not need a code change to work.
     */
    suspend fun load(directory: File): Boolean = withContext(Dispatchers.Default) {
        if (tts != null) return@withContext true

        val model = directory.walkTopDown().maxDepth(WALK_DEPTH)
            .firstOrNull { it.isFile && it.extension == "onnx" }
        if (model == null) {
            Log.e(TAG, "piper: no .onnx under ${directory.absolutePath}")
            return@withContext false
        }
        val home = model.parentFile ?: directory
        val tokens = File(home, "tokens.txt")
        val espeak = File(home, "espeak-ng-data")
        if (!tokens.isFile || !espeak.isDirectory) {
            Log.e(TAG, "piper: ${home.name} is missing tokens.txt or espeak-ng-data")
            return@withContext false
        }

        runCatching {
            OfflineTts(
                assetManager = null,
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = model.absolutePath,
                            tokens = tokens.absolutePath,
                            dataDir = espeak.absolutePath,
                        ),
                        numThreads = THREADS,
                        debug = false,
                    ),
                ),
            )
        }.onSuccess {
            tts = it
            Log.i(TAG, "piper: loaded ${model.name} at ${it.sampleRate()} Hz")
        }.onFailure {
            Log.e(TAG, "piper: could not load ${model.name}", it)
        }
        tts != null
    }

    /**
     * Speaks [text], returning once the audio has finished.
     *
     * Silently does nothing when no voice is loaded: the assistant still shows its reply
     * on screen, and a missing voice should degrade the experience rather than end it.
     */
    suspend fun speak(text: String): Boolean = withContext(Dispatchers.Default) {
        val engine = tts ?: return@withContext false
        if (text.isBlank()) return@withContext false

        val audio = runCatching { engine.generate(text, SPEAKER, SPEED) }.getOrElse {
            Log.e(TAG, "piper: synthesis failed", it)
            return@withContext false
        }
        play(audio.samples, audio.sampleRate)
    }

    private fun play(samples: FloatArray, sampleRate: Int): Boolean {
        if (samples.isEmpty()) return false
        stop()

        val minimum = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bytes = samples.size * Float.SIZE_BYTES
        val player = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // ASSISTANT so the phone ducks music rather than talking over it.
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum, bytes))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrElse {
            Log.e(TAG, "piper: no audio track", it)
            return false
        }

        track = player
        player.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        player.play()

        // MODE_STATIC plays from a buffer already written, so waiting means watching the
        // playback head rather than waiting on the write to drain.
        val total = samples.size
        while (player.playState == AudioTrack.PLAYSTATE_PLAYING &&
            player.playbackHeadPosition < total
        ) {
            Thread.sleep(POLL_MILLIS)
        }
        stop()
        return true
    }

    /** Cuts playback short, for a reply the user has already moved on from. */
    fun stop() {
        track?.runCatching {
            if (state == AudioTrack.STATE_INITIALIZED) {
                pause()
                flush()
            }
            release()
        }
        track = null
    }

    fun release() {
        stop()
        tts?.runCatching { release() }
        tts = null
    }

    private companion object {
        const val TAG = "TheMachine"
        const val THREADS = 2
        const val SPEAKER = 0
        const val SPEED = 1.0f
        const val WALK_DEPTH = 3
        const val POLL_MILLIS = 20L
    }
}
