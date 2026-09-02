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

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import io.github.hasanismail.themachine.stt.WhisperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.sqrt

/** What the recorder is doing, for the overlay to draw. */
sealed interface CaptureEvent {
    /** Live level in [0, 1], for the meter. Emitted per frame. */
    data class Level(val amplitude: Float) : CaptureEvent

    /** Speech detected; the trailing-silence timer is now meaningful. */
    data object SpeechStarted : CaptureEvent

    /** Capture finished with audio to transcribe. */
    data class Finished(val samples: FloatArray, val reason: StopReason) : CaptureEvent {
        // Arrays compare by identity, which would make the generated equals misleading.
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Finished && reason == other.reason && samples.contentEquals(other.samples))

        override fun hashCode(): Int = HASH_SEED * samples.contentHashCode() + reason.hashCode()

        private companion object {
            const val HASH_SEED = 31
        }
    }

    /** Capture could not start. */
    data class Failed(val reason: String) : CaptureEvent
}

enum class StopReason {
    /** Trailing silence after speech — the normal ending. */
    ENDPOINT,

    /** Hit the hard cap without ever detecting an end. */
    MAX_DURATION,

    /** The caller stopped it. */
    CANCELLED,

    /** Nothing above the noise floor for long enough that there is nothing to send. */
    NO_SPEECH,
}

/**
 * Opens the microphone, streams level events, and stops when [Endpointer] says the
 * speaker has finished.
 *
 * Cold flow: capture starts on collection and the AudioRecord is released when
 * collection ends, so cancelling a session cannot leave the microphone open.
 */
class VoiceRecorder {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun capture(endpointer: Endpointer = Endpointer()): Flow<CaptureEvent> = callbackFlow {
        val minBuffer = AudioRecord.getMinBufferSize(
            WhisperEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            trySend(CaptureEvent.Failed("This device cannot record at 16 kHz mono."))
            close()
            return@callbackFlow
        }

        val record = buildRecord(minBuffer)
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            trySend(CaptureEvent.Failed("Microphone unavailable — is another app using it?"))
            close()
            return@callbackFlow
        }

        try {
            record.startRecording()
            pump(record, endpointer)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "capture failed", e)
            trySend(CaptureEvent.Failed(e.message ?: "Recording failed"))
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        close()

        awaitClose { runCatching { record.release() } }
    }.flowOn(Dispatchers.IO)

    /** Reads frames until the endpointer stops it or the flow is cancelled. */
    private suspend fun ProducerScope<CaptureEvent>.pump(
        record: AudioRecord,
        endpointer: Endpointer,
    ) {
        val collected = ArrayList<Float>(WhisperEngine.SAMPLE_RATE * INITIAL_SECONDS)
        val buffer = ShortArray(FRAME_SAMPLES)

        while (currentCoroutineContext().isActive) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) continue

            val rms = appendAndMeasure(buffer, read, collected)
            trySend(CaptureEvent.Level(displayLevel(rms)))

            when (val verdict = endpointer.accept(rms)) {
                FrameVerdict.Continue -> Unit

                FrameVerdict.SpeechBegan -> trySend(CaptureEvent.SpeechStarted)

                is FrameVerdict.Stop -> {
                    val samples = if (verdict.reason == StopReason.NO_SPEECH) {
                        FloatArray(0)
                    } else {
                        collected.toFloatArray()
                    }
                    trySend(CaptureEvent.Finished(samples, verdict.reason))
                    return
                }
            }
        }
        trySend(CaptureEvent.Finished(collected.toFloatArray(), StopReason.CANCELLED))
    }

    /** Converts one frame to float, accumulates it, and returns its RMS. */
    private fun appendAndMeasure(buffer: ShortArray, read: Int, into: MutableList<Float>): Float {
        var sumSquares = 0.0
        for (i in 0 until read) {
            val sample = buffer[i] / Short.MAX_VALUE.toFloat()
            into.add(sample)
            sumSquares += (sample * sample).toDouble()
        }
        return sqrt(sumSquares / read).toFloat()
    }

    /**
     * VOICE_RECOGNITION rather than MIC: it asks the platform for the processing chain
     * tuned for speech — the same one system dictation uses — instead of a flat capture.
     */
    @SuppressLint("MissingPermission")
    private fun buildRecord(minBuffer: Int): AudioRecord? = runCatching {
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            WhisperEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_SAMPLES * Short.SIZE_BYTES) * BUFFER_MULTIPLIER,
        )
    }.getOrNull()

    /** RMS is tiny for speech; a square root spreads the useful range across the meter. */
    private fun displayLevel(rms: Float): Float = sqrt(rms.coerceIn(0f, 1f))

    private companion object {
        const val TAG = "TheMachine"

        /** 20 ms per frame — fine enough to endpoint quickly, coarse enough to be cheap. */
        val FRAME_SAMPLES = (WhisperEngine.SAMPLE_RATE * Endpointer.DEFAULT_FRAME_MILLIS / 1000).toInt()

        const val BUFFER_MULTIPLIER = 4
        const val INITIAL_SECONDS = 4
    }
}
