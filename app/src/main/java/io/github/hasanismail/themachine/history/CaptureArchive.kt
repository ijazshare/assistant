/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.history

import android.content.Context
import android.util.Log
import io.github.hasanismail.themachine.BuildConfig
import io.github.hasanismail.themachine.audio.Captured
import io.github.hasanismail.themachine.audio.FrameMeter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * One microphone capture as the endpointer saw it: how it ended, and every frame's levels,
 * exactly as the endpointer was given them.
 *
 * Records written before the endpointer listened above 400 Hz hold the raw RMS in [levels] and
 * nothing in [highBandLevels]; they decode with [levelsFrontEnd] set to [CaptureArchive.RAW_LEVELS].
 */
@Serializable
data class CaptureRecord(
    val atEpochMillis: Long,
    val reason: String,
    val source: Int,
    val frameMillis: Long,
    /** The WAV of this capture, in the same folder as the index: the audio as recorded, unfiltered. */
    val recording: String,
    /** Per frame, the RMS above 400 Hz: the level the endpointer judges speech on. */
    val levels: List<Float>,
    /** Per frame, the RMS above 1.5 kHz: the endpointer's second input. */
    val highBandLevels: List<Float> = emptyList(),
    /** How [levels] and [highBandLevels] were measured ([FrameMeter.FRONT_END] for new records). */
    val levelsFrontEnd: String = CaptureArchive.RAW_LEVELS,
    /** Whether the voice check ran to the end of the capture: its model was present and never failed. */
    val voiceCheck: Boolean = false,
    /** What ended the capture: "energy" or "voice"; empty if nothing did (a dismissal). */
    val stoppedBy: String = "",
    /** Whether the voice check heard a voice for certain, and the frame (from 1) it did on; 0 if never. */
    val armedFrame: Int = 0,
)

/**
 * Keeps recent captures on the device: each one's audio as a WAV, and every frame's levels
 * in an index beside them, so a capture that ran on can be listened to and its levels
 * replayed through the endpointer offline. The levels are kept as the endpointer had them,
 * unrounded, so a replay reaches the same decisions the phone did.
 *
 * Temporary, and debug builds only. Added on 2026-10-26 at the user's request while
 * end-of-speech detection is being fixed; once it works this goes, or becomes a setting.
 * Release builds never write audio. Like the rest of the history, nothing here leaves the
 * phone.
 */
class CaptureArchive(private val dir: File, val enabled: Boolean, private val keep: Int = KEEP) {

    constructor(context: Context) : this(
        File(File(context.getExternalFilesDir(null), "history"), DIR_NAME),
        enabled = BuildConfig.DEBUG,
    )

    private val json = Json { encodeDefaults = true }

    @Synchronized
    fun save(atEpochMillis: Long, capture: Captured) {
        if (!enabled) return
        runCatching {
            dir.mkdirs()
            val reason = capture.reason.name
            val name = "${STAMP.format(Instant.ofEpochMilli(atEpochMillis))}-${reason.lowercase()}.wav"
            File(dir, name).writeBytes(wav(capture.samples))
            val record = CaptureRecord(
                atEpochMillis = atEpochMillis,
                reason = reason,
                source = capture.source,
                frameMillis = FRAME_MILLIS,
                recording = name,
                levels = capture.levels.toList(),
                highBandLevels = capture.highBandLevels.toList(),
                levelsFrontEnd = FrameMeter.FRONT_END,
                voiceCheck = capture.voiceCheck.active,
                stoppedBy = capture.voiceCheck.stoppedBy?.name?.lowercase().orEmpty(),
                armedFrame = capture.voiceCheck.armedFrame,
            )
            File(dir, INDEX).appendText(json.encodeToString(record) + "\n")
            prune()
        }.onFailure { Log.w(TAG, "captures: could not save", it) }
    }

    /** Keeps the newest [keep] recordings and index lines. Names start with the time, so they sort oldest first. */
    private fun prune() {
        dir.listFiles { file -> file.name.endsWith(".wav") }.orEmpty()
            .sortedBy { it.name }
            .dropLast(keep)
            .forEach { it.delete() }
        val index = File(dir, INDEX)
        val lines = index.readLines()
        if (lines.size > keep) index.writeText(lines.takeLast(keep).joinToString("\n", postfix = "\n"))
    }

    companion object {
        private const val TAG = "TheMachine"
        const val DIR_NAME = "captures"
        const val INDEX = "captures.jsonl"

        /**
         * About 75 MB of ordinary commands; 320 MB if every one of them ran to the 20 s cap. The
         * index adds about 1.3 KB per second of capture: two levels a frame, unrounded.
         */
        const val KEEP = 500

        /** The recorder's frame, which every level stands for. */
        const val FRAME_MILLIS = 20L

        /** [CaptureRecord.levelsFrontEnd] of records whose levels are the raw RMS. */
        const val RAW_LEVELS = "raw"

        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())

        private const val SAMPLE_RATE = 16_000
        private const val HEADER_BYTES = 44
        private const val RIFF_SIZE_EXCLUDED = 8
        private const val FMT_CHUNK_BYTES = 16
        private const val PCM: Short = 1
        private const val MONO: Short = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val BITS_PER_SAMPLE: Short = 16

        /** [samples] in [-1, 1] as 16-bit mono PCM WAV; anything outside is clipped. */
        fun wav(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val dataBytes = samples.size * BYTES_PER_SAMPLE
            val out = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            out.put(
                "RIFF".toByteArray(),
            ).putInt(HEADER_BYTES - RIFF_SIZE_EXCLUDED + dataBytes).put("WAVE".toByteArray())
            out.put("fmt ".toByteArray()).putInt(FMT_CHUNK_BYTES).putShort(PCM).putShort(MONO)
                .putInt(sampleRate).putInt(sampleRate * BYTES_PER_SAMPLE)
                .putShort(BYTES_PER_SAMPLE.toShort()).putShort(BITS_PER_SAMPLE)
            out.put("data".toByteArray()).putInt(dataBytes)
            for (sample in samples) {
                out.putShort((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort())
            }
            return out.array()
        }
    }
}
