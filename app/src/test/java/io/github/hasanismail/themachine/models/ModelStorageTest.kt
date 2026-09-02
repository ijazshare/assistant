/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.models

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/**
 * The checksum and resume bookkeeping — the part that decides whether a downloaded
 * gigabyte is trusted or thrown away.
 */
class ModelStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: ModelStorage

    private val payload = "the machine".toByteArray()
    private val payloadSha = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { "%02x".format(it) }

    private val asset = ModelAsset(
        id = "test",
        role = ModelRole.STT,
        displayName = "Test",
        detail = "",
        fileName = "test.bin",
        url = "https://example.invalid/test.bin",
        sha256 = payloadSha,
        byteSize = payload.size.toLong(),
        minRamMb = 1,
    )

    @Before
    fun setUp() {
        storage = ModelStorage(tempFolder.newFolder("models"))
    }

    @Test
    fun `absent when nothing has been written`() {
        assertThat(storage.quickState(asset)).isEqualTo(ModelState.Absent)
    }

    @Test
    fun `a part file reports as resumable with its byte count`() {
        storage.partial(asset).writeBytes(payload.copyOf(4))
        assertThat(storage.quickState(asset)).isEqualTo(ModelState.Partial(4))
    }

    @Test
    fun `a complete file of the right size reports ready`() {
        storage.target(asset).writeBytes(payload)
        assertThat(storage.quickState(asset)).isEqualTo(ModelState.Ready)
    }

    @Test
    fun `a file of the wrong size reports corrupt without hashing`() {
        storage.target(asset).writeBytes(payload + 0)
        assertThat(storage.quickState(asset)).isInstanceOf(ModelState.Corrupt::class.java)
    }

    @Test
    fun `verify accepts the exact bytes`() {
        storage.target(asset).writeBytes(payload)
        assertThat(storage.verify(asset)).isTrue()
    }

    @Test
    fun `verify rejects same-length different bytes`() {
        // The size check alone would pass here. This is the case that makes hashing
        // worth the seconds it costs: a truncated-then-padded or corrupted transfer.
        val corrupted = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }
        storage.target(asset).writeBytes(corrupted)
        assertThat(storage.verify(asset)).isFalse()
    }

    @Test
    fun `delete removes both the finished file and any partial`() {
        storage.target(asset).writeBytes(payload)
        storage.partial(asset).writeBytes(payload)
        storage.delete(asset)
        assertThat(storage.target(asset).exists()).isFalse()
        assertThat(storage.partial(asset).exists()).isFalse()
        assertThat(storage.quickState(asset)).isEqualTo(ModelState.Absent)
    }

    @Test
    fun `sha256 matches a known digest`() {
        val file = storage.target(asset).apply { writeBytes(payload) }
        assertThat(storage.sha256(file)).isEqualTo(payloadSha)
    }

    @Test
    fun `an asset larger than the volume is refused before downloading`() {
        val huge = asset.copy(byteSize = Long.MAX_VALUE / 2)
        assertThat(storage.hasRoomFor(huge)).isFalse()
    }
}
