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

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** The model's ownership and pinning rules, on a fake engine: no native code, no model file. */
class SileroVoiceModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** An engine whose memory is the list of tags it was given since the last reset. */
    private class FakeEngine : SileroVoiceModel.Engine {
        var memory = ArrayList<Int>()
        var released = false
        var tag = 0

        override fun compute(window: FloatArray): Float {
            check(!released)
            memory.add(tag)
            return 0.5f
        }

        override fun reset() {
            memory = ArrayList()
        }

        override fun release() {
            released = true
        }
    }

    private val window = FloatArray(VoiceCheckLayer.WINDOW)

    @Test
    fun `a capture that was taken over cannot add to the newer capture's memory`() {
        val engine = FakeEngine()
        val model = SileroVoiceModel(engine)
        val old = model.newCapture()
        old.reset()
        engine.tag = 1
        old.probability(window)

        val new = model.newCapture()
        new.reset()
        // The previous pump's last frame, after the new capture began.
        assertThrows(IllegalStateException::class.java) { old.probability(window) }
        assertThat(engine.memory).isEmpty()

        engine.tag = 2
        new.probability(window)
        assertThat(engine.memory).containsExactly(2)
    }

    @Test
    fun `a capture that never took the model over cannot use it`() {
        val model = SileroVoiceModel(FakeEngine())
        assertThrows(IllegalStateException::class.java) { model.newCapture().probability(window) }
    }

    @Test
    fun `a layer on a taken-over capture turns its check off instead of failing`() {
        val model = SileroVoiceModel(FakeEngine())
        val oldLayer = VoiceCheckLayer({ _, _ -> FrameVerdict.Continue }, model.newCapture())
        VoiceCheckLayer({ _, _ -> FrameVerdict.Continue }, model.newCapture())

        repeat(40) { oldLayer.accept(0f, 0f, FloatArray(320), 320) }

        assertThat(oldLayer.active).isFalse()
        assertThat(oldLayer.failure).hasMessageThat().contains("newer capture")
    }

    @Test
    fun `a released model throws, to every capture`() {
        val engine = FakeEngine()
        val model = SileroVoiceModel(engine)
        val capture = model.newCapture()
        capture.reset()
        model.release()
        model.release()

        assertThat(engine.released).isTrue()
        assertThrows(IllegalStateException::class.java) { capture.probability(window) }
        assertThrows(IllegalStateException::class.java) { model.newCapture().reset() }
    }

    @Test
    fun `a window of any other length never reaches the engine`() {
        val engine = FakeEngine()
        val capture = SileroVoiceModel(engine).newCapture()
        capture.reset()
        for (n in intArrayOf(0, 512, 575, 577, 1_024)) {
            assertThrows(IllegalArgumentException::class.java) { capture.probability(FloatArray(n)) }
        }
        assertThat(engine.memory).isEmpty()
    }

    // ---- the pinned file -------------------------------------------------------------------------

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `only the pinned size and hash are accepted`() {
        val bytes = ByteArray(1_000) { (it * 7).toByte() }
        val file = folder.newFile("model.onnx").apply { writeBytes(bytes) }
        val sha = sha256(bytes)

        SileroVoiceModel.requirePinned(file, sha, 1_000)
        SileroVoiceModel.requirePinned(file, sha.uppercase(), 1_000)
        // Same bytes, another size.
        assertThrows(IllegalArgumentException::class.java) { SileroVoiceModel.requirePinned(file, sha, 999) }
        // Same size, another file: a 512-sample model is not told apart by its length.
        val other = folder.newFile("other.onnx").apply { writeBytes(bytes.reversedArray()) }
        assertThrows(IllegalArgumentException::class.java) { SileroVoiceModel.requirePinned(other, sha, 1_000) }
        assertThrows(IllegalArgumentException::class.java) {
            SileroVoiceModel.requirePinned(File(folder.root, "absent.onnx"), sha, 1_000)
        }
    }

    @Test
    fun `open refuses any file but the pinned one before it reaches native code`() {
        val wrongSize = folder.newFile("v4.onnx").apply { writeBytes(ByteArray(10)) }
        assertThrows(IllegalArgumentException::class.java) { SileroVoiceModel.open(wrongSize) }
        // The pinned size, other contents: what a second VAD entry in the registry could look like.
        val wrongBytes = folder.newFile("v4-same-size.onnx").apply {
            writeBytes(ByteArray(SileroVoiceModel.BYTES.toInt()))
        }
        assertThrows(IllegalArgumentException::class.java) { SileroVoiceModel.open(wrongBytes) }
    }
}
