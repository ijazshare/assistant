/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.wake

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.hasanismail.themachine.models.ModelArchive
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The wake word against real speech.
 *
 * Two claims worth holding: that the phrase wakes it, and that ordinary speech does not.
 * The second matters more — a detector that fires on everything is worse than none, and
 * the failure it causes is a microphone opening while someone is talking about something
 * else.
 */
@RunWith(AndroidJUnit4::class)
class WakeWordEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = ModelStorage(context)

    private fun unpackedModel(): java.io.File? {
        val asset = ModelRegistry(context).byRole(ModelRole.WAKE)
            .firstOrNull { storage.quickState(it) == ModelState.Ready } ?: return null
        if (!ModelArchive.unpack(storage.target(asset), storage.extractedDir(asset))) return null
        return storage.extractedDir(asset)
    }

    /** 16-bit mono PCM from the test assets, as the float samples the spotter wants. */
    private fun samples(asset: String): FloatArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(asset).use { it.readBytes() }
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val count = minOf(size, bytes.size - offset - 8) / 2
                val pcm = ByteBuffer.wrap(bytes, offset + 8, count * 2).order(ByteOrder.LITTLE_ENDIAN)
                return FloatArray(count) { pcm.short / Short.MAX_VALUE.toFloat() }
            }
            offset += 8 + size + (size and 1)
        }
        error("no data chunk in $asset")
    }

    /** Feeds a recording in the same chunks the service does, and says whether it fired. */
    private fun heard(engine: WakeWordEngine, asset: String): Boolean {
        val audio = samples(asset)
        var fired = false
        var at = 0
        while (at < audio.size) {
            val end = minOf(at + WakeWordService.CHUNK_SAMPLES, audio.size)
            if (engine.accept(audio.copyOfRange(at, end))) fired = true
            at = end
        }
        // Trailing silence, because a phrase at the very end of a recording needs the
        // decoder to be given something after it before it will commit.
        repeat(TAIL_CHUNKS) {
            if (engine.accept(FloatArray(WakeWordService.CHUNK_SAMPLES))) fired = true
        }
        return fired
    }

    @Test
    fun heyRootWakesItAndOtherSpeechDoesNot() {
        val model = unpackedModel()
        assumeTrue("No wake word model installed", model != null)

        val engine = WakeWordEngine(model!!)
        try {
            assertThat(engine.load()).isTrue()

            val started = System.nanoTime()
            val woke = heard(engine, "heyroot.wav")
            val millis = (System.nanoTime() - started) / 1_000_000
            Log.i(TAG, "WAKE heyroot -> $woke in $millis ms")
            assertThat(woke).isTrue()

            val other = heard(engine, "notheyroot.wav")
            Log.i(TAG, "WAKE other speech -> $other")
            assertThat(other).isFalse()
        } finally {
            engine.release()
        }
    }

    @Test
    fun silenceNeverWakesIt() {
        val model = unpackedModel()
        assumeTrue("No wake word model installed", model != null)

        val engine = WakeWordEngine(model!!)
        try {
            assertThat(engine.load()).isTrue()
            repeat(SILENT_CHUNKS) {
                assertThat(engine.accept(FloatArray(WakeWordService.CHUNK_SAMPLES))).isFalse()
            }
        } finally {
            engine.release()
        }
    }

    @Test
    fun nothingIsHeardWithoutAModel() {
        val engine = WakeWordEngine(java.io.File(context.filesDir, "no-such-model"))
        assertThat(engine.load()).isFalse()
        assertThat(engine.accept(FloatArray(WakeWordService.CHUNK_SAMPLES))).isFalse()
        engine.release()
    }

    private companion object {
        const val TAG = "TheMachine"
        const val TAIL_CHUNKS = 4
        const val SILENT_CHUNKS = 20
    }
}
