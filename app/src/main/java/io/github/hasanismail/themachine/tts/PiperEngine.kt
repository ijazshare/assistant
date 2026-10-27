/*
 * Assistant — an offline voice assistant for Android.
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
 *
 * Every use of the native synthesiser happens under [engineLock], and so does freeing
 * it. Without that, dismissing the overlay while a reply was still being synthesised
 * freed the engine under a thread that was reading from it — a SIGSEGV, found the first
 * time a test tore a session down promptly.
 *
 * Starting playback and [stop] both happen under [playbackLock], so a stop can never land
 * between a reply's last check and its first sample.
 */
class PiperEngine {

    private val engineLock = Any()

    /** Guards [track] and [stops]. Never held across synthesis, so stop() returns at once. */
    private val playbackLock = Any()

    private var tts: OfflineTts? = null

    private var track: AudioTrack? = null

    /**
     * Calls to [stop] so far. A reply notes the count when it is asked for and plays only if
     * it has not moved since. Synthesis takes long enough that a stop used to land in the
     * middle of it, and the reply then started anyway, after the stop meant to silence it.
     */
    private var stops = 0

    @Volatile
    private var closing = false

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

        val loaded = runCatching {
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
        }.onFailure { Log.e(TAG, "piper: could not load ${model.name}", it) }.getOrNull()

        synchronized(engineLock) {
            if (closing || tts != null) {
                loaded?.release()
            } else {
                tts = loaded
                if (loaded != null) Log.i(TAG, "piper: loaded ${model.name} at ${loaded.sampleRate()} Hz")
            }
        }
        tts != null
    }

    /**
     * Speaks [text], returning once the audio has finished.
     *
     * Silently does nothing when no voice is loaded: the assistant still shows its reply
     * on screen, and a missing voice should degrade the experience rather than end it.
     * Nor is anything played if [stop] is called after this was, even while it is still
     * being synthesised.
     */
    suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        // Noted before synthesis, so a stop() that lands during it still counts.
        val turn = synchronized(playbackLock) { stops }
        return withContext(Dispatchers.Default) {
            // Synthesis under the lock, so release() cannot free the engine mid-sentence.
            val audio = synchronized(engineLock) {
                val engine = tts
                if (engine == null || closing) {
                    Log.w(TAG, "piper: nothing to speak with (loaded=${engine != null}, closing=$closing)")
                    return@withContext false
                }
                runCatching { engine.generate(text, SPEAKER, SPEED) }.getOrElse {
                    Log.e(TAG, "piper: synthesis failed", it)
                    return@withContext false
                }
            }
            play(audio.samples, audio.sampleRate, turn)
        }
    }

    private fun play(samples: FloatArray, sampleRate: Int, turn: Int): Boolean {
        if (samples.isEmpty() || closing) return false

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
        player.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)

        // Checked and started under the lock stop() takes, so a stop() made since this reply
        // was asked for keeps it silent, and one made after it starts cuts it off.
        synchronized(playbackLock) {
            if (turn != stops || closing) {
                player.release()
                Log.i(TAG, "piper: reply stopped before it started")
                return false
            }
            // A newer reply replaces one still playing, as it always has.
            releaseTrack()
            track = player
            player.play()
        }
        Log.i(TAG, "piper: speaking ${samples.size * MILLIS_PER_SECOND / sampleRate} ms")

        // MODE_STATIC plays from a buffer already written, so waiting means watching the
        // playback head rather than waiting on the write to drain. A track released from
        // another thread by stop() throws here, which simply means playback is over.
        val total = samples.size
        while (
            runCatching {
                player.playState == AudioTrack.PLAYSTATE_PLAYING && player.playbackHeadPosition < total
            }.getOrDefault(false)
        ) {
            Thread.sleep(POLL_MILLIS)
        }
        // Only if it is still this reply's track: a newer reply may have replaced it.
        synchronized(playbackLock) { if (track === player) releaseTrack() }
        return true
    }

    /**
     * Cuts playback short, for a reply the user has already moved on from. A reply still
     * being synthesised is dropped too: it will not start afterwards.
     */
    fun stop() {
        synchronized(playbackLock) {
            stops++
            releaseTrack()
        }
    }

    /** Stops and frees the current track. The caller holds [playbackLock]. */
    private fun releaseTrack() {
        track?.runCatching {
            if (state == AudioTrack.STATE_INITIALIZED) {
                pause()
                flush()
            }
            release()
        }
        track = null
    }

    /**
     * Frees the voice. Waits for a synthesis in flight rather than pulling the engine out
     * from under it; that wait is one short sentence at most.
     */
    fun release() {
        closing = true
        stop()
        synchronized(engineLock) {
            tts?.runCatching { release() }
            tts = null
        }
    }

    private companion object {
        const val TAG = "TheMachine"
        const val THREADS = 2
        const val SPEAKER = 0
        const val SPEED = 1.0f
        const val WALK_DEPTH = 3
        const val POLL_MILLIS = 20L
        const val MILLIS_PER_SECOND = 1000
    }
}
