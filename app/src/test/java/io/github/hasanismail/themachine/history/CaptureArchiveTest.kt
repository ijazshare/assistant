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

import com.google.common.truth.Truth.assertThat
import io.github.hasanismail.themachine.audio.Captured
import io.github.hasanismail.themachine.audio.FrameMeter
import io.github.hasanismail.themachine.audio.StopReason
import io.github.hasanismail.themachine.audio.VoiceCheckLayer
import io.github.hasanismail.themachine.audio.VoiceCheckReport
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.nextDown
import kotlin.math.nextUp

class CaptureArchiveTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun wavs(): List<File> = folder.root.listFiles { file ->
        file.name.endsWith(".wav")
    }.orEmpty().sortedBy { it.name }

    private fun index(): List<CaptureRecord> =
        File(folder.root, CaptureArchive.INDEX).readLines().map { Json.decodeFromString<CaptureRecord>(it) }

    private fun captured(
        samples: FloatArray,
        levels: FloatArray,
        highBandLevels: FloatArray = FloatArray(levels.size),
        reason: StopReason = StopReason.ENDPOINT,
        voiceCheck: VoiceCheckReport = VoiceCheckReport(active = false, armedFrame = 0, stoppedBy = null),
    ) = Captured(samples, levels, highBandLevels, reason, source = 6, voiceCheck = voiceCheck)

    @Test
    fun `a capture becomes a wav and an index line that names it`() {
        CaptureArchive(folder.root, enabled = true).save(
            1_000L,
            captured(floatArrayOf(0f, 0.5f, -0.5f), floatArrayOf(0.01234567f, 0.5f), floatArrayOf(0.002f, 0.25f)),
        )

        val wav = wavs().single()
        assertThat(wav.length()).isEqualTo(44L + 3 * 2)
        assertThat(wav.name).endsWith("-endpoint.wav")
        val record = index().single()
        assertThat(record.recording).isEqualTo(wav.name)
        assertThat(record.reason).isEqualTo("ENDPOINT")
        assertThat(record.source).isEqualTo(6)
        assertThat(record.frameMillis).isEqualTo(20L)
        // Kept as the endpointer was given them. These were rounded to five decimals (0.01235)
        // until the high band came in: its quiet-room levels sit near 0.001, where that rounding
        // can move a level across one of the endpointer's comparisons, and a replay would then
        // decide differently from the phone.
        assertThat(record.levels).containsExactly(0.01234567f, 0.5f).inOrder()
        assertThat(record.highBandLevels).containsExactly(0.002f, 0.25f).inOrder()
        assertThat(record.levelsFrontEnd).isEqualTo(FrameMeter.FRONT_END)
    }

    @Test
    fun `the levels read back exactly as the endpointer was given them`() {
        val levels = floatArrayOf(1e-7f, 0.0011f, 1f / 3, 0.004f.nextUp(), 0.004f.nextDown(), 0.08f)
        CaptureArchive(folder.root, enabled = true)
            .save(1_000L, captured(FloatArray(2), levels, highBandLevels = levels.reversedArray()))

        val record = index().single()
        assertThat(record.levels.toFloatArray()).isEqualTo(levels)
        assertThat(record.highBandLevels.toFloatArray()).isEqualTo(levels.reversedArray())
    }

    @Test
    fun `an index line from before the high band still reads, as raw levels`() {
        val line = """{"atEpochMillis":1000,"reason":"ENDPOINT","source":6,"frameMillis":20,""" +
            """"recording":"19700101-000001-000-endpoint.wav","levels":[0.01235,0.5]}"""

        val record = Json.decodeFromString<CaptureRecord>(line)

        assertThat(record.levels).containsExactly(0.01235f, 0.5f).inOrder()
        assertThat(record.highBandLevels).isEmpty()
        assertThat(record.levelsFrontEnd).isEqualTo(CaptureArchive.RAW_LEVELS)
    }

    @Test
    fun `what the voice check did is recorded with the capture`() {
        val archive = CaptureArchive(folder.root, enabled = true)
        val ended = VoiceCheckReport(active = true, armedFrame = 61, stoppedBy = VoiceCheckLayer.StoppedBy.VOICE)
        archive.save(1_000L, captured(FloatArray(2), FloatArray(2), voiceCheck = ended))
        val without = VoiceCheckReport(active = false, armedFrame = 0, stoppedBy = null)
        archive.save(2_000L, captured(FloatArray(2), FloatArray(2), voiceCheck = without))

        val (voice, none) = index()
        assertThat(voice.voiceCheck).isTrue()
        assertThat(voice.stoppedBy).isEqualTo("voice")
        assertThat(voice.armedFrame).isEqualTo(61)
        assertThat(none.voiceCheck).isFalse()
        assertThat(none.stoppedBy).isEmpty()
        assertThat(none.armedFrame).isEqualTo(0)
    }

    @Test
    fun `an index line from before the voice check still reads, as no check`() {
        val line = """{"atEpochMillis":1000,"reason":"ENDPOINT","source":6,"frameMillis":20,""" +
            """"recording":"19700101-000001-000-endpoint.wav","levels":[0.5],"highBandLevels":[0.1],""" +
            """"levelsFrontEnd":"hp400o4+hp1500o4"}"""

        val record = Json.decodeFromString<CaptureRecord>(line)

        assertThat(record.voiceCheck).isFalse()
        assertThat(record.stoppedBy).isEmpty()
        assertThat(record.armedFrame).isEqualTo(0)
    }

    @Test
    fun `a release build writes nothing`() {
        CaptureArchive(folder.root, enabled = false).save(1_000L, captured(floatArrayOf(0.1f), floatArrayOf(0.1f)))

        assertThat(folder.root.listFiles()).isEmpty()
    }

    @Test
    fun `only the newest captures are kept`() {
        val archive = CaptureArchive(folder.root, enabled = true, keep = 2)
        listOf(1_000L, 2_000L, 3_000L).forEach {
            archive.save(it, captured(floatArrayOf(0f), floatArrayOf(0f), reason = StopReason.MAX_DURATION))
        }

        assertThat(wavs()).hasSize(2)
        assertThat(index().map { it.atEpochMillis }).containsExactly(2_000L, 3_000L).inOrder()
        assertThat(wavs().map { it.name }).containsExactlyElementsIn(index().map { it.recording })
    }

    @Test
    fun `the wav is 16-bit mono PCM at 16 kHz, clipped to full scale`() {
        val bytes = ByteBuffer.wrap(CaptureArchive.wav(floatArrayOf(1.5f, -1f, 0f))).order(ByteOrder.LITTLE_ENDIAN)

        fun tag(at: Int) = String(bytes.array(), at, 4)
        assertThat(tag(0)).isEqualTo("RIFF")
        assertThat(bytes.getInt(4)).isEqualTo(36 + 6)
        assertThat(tag(8)).isEqualTo("WAVE")
        assertThat(tag(12)).isEqualTo("fmt ")
        assertThat(bytes.getInt(16)).isEqualTo(16)
        assertThat(bytes.getShort(20)).isEqualTo(1.toShort())
        assertThat(bytes.getShort(22)).isEqualTo(1.toShort())
        assertThat(bytes.getInt(24)).isEqualTo(16_000)
        assertThat(bytes.getInt(28)).isEqualTo(32_000)
        assertThat(bytes.getShort(32)).isEqualTo(2.toShort())
        assertThat(bytes.getShort(34)).isEqualTo(16.toShort())
        assertThat(tag(36)).isEqualTo("data")
        assertThat(bytes.getInt(40)).isEqualTo(6)
        assertThat(bytes.getShort(44)).isEqualTo(Short.MAX_VALUE)
        assertThat(bytes.getShort(46)).isEqualTo((-Short.MAX_VALUE).toShort())
        assertThat(bytes.getShort(48)).isEqualTo(0.toShort())
    }
}
