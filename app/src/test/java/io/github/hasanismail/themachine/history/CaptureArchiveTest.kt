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
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CaptureArchiveTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun wavs(): List<File> = folder.root.listFiles { file ->
        file.name.endsWith(".wav")
    }.orEmpty().sortedBy { it.name }

    private fun index(): List<CaptureRecord> =
        File(folder.root, CaptureArchive.INDEX).readLines().map { Json.decodeFromString<CaptureRecord>(it) }

    @Test
    fun `a capture becomes a wav and an index line that names it`() {
        CaptureArchive(folder.root, enabled = true)
            .save(1_000L, floatArrayOf(0f, 0.5f, -0.5f), floatArrayOf(0.01234567f, 0.5f), "ENDPOINT", 6)

        val wav = wavs().single()
        assertThat(wav.length()).isEqualTo(44L + 3 * 2)
        assertThat(wav.name).endsWith("-endpoint.wav")
        val record = index().single()
        assertThat(record.recording).isEqualTo(wav.name)
        assertThat(record.reason).isEqualTo("ENDPOINT")
        assertThat(record.source).isEqualTo(6)
        assertThat(record.frameMillis).isEqualTo(20L)
        assertThat(record.levels).containsExactly(0.01235f, 0.5f).inOrder()
    }

    @Test
    fun `a release build writes nothing`() {
        CaptureArchive(folder.root, enabled = false).save(1_000L, floatArrayOf(0.1f), floatArrayOf(0.1f), "ENDPOINT", 6)

        assertThat(folder.root.listFiles()).isEmpty()
    }

    @Test
    fun `only the newest captures are kept`() {
        val archive = CaptureArchive(folder.root, enabled = true, keep = 2)
        listOf(1_000L, 2_000L, 3_000L).forEach {
            archive.save(it, floatArrayOf(0f), floatArrayOf(0f), "MAX_DURATION", 6)
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
