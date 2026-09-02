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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.hasanismail.themachine.models.ModelArchive
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The voice, end to end: unpack the downloaded archive, load it, say something.
 *
 * Playback itself is not asserted — a test cannot hear — so this stops at the audio the
 * synthesiser produced, which is where the interesting failures are anyway: a missing
 * phonemiser directory or a model that will not load both show up here.
 */
@RunWith(AndroidJUnit4::class)
class PiperEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = ModelStorage(context)

    private fun voice() = ModelRegistry(context).byRole(ModelRole.TTS)
        .filter { storage.quickState(it) == ModelState.Ready }
        .let { ready -> ready.firstOrNull { it.isDefault } ?: ready.firstOrNull() }

    @Test
    fun theVoiceUnpacksLoadsAndSpeaks() {
        val asset = voice()
        assumeTrue("No voice installed on this device", asset != null)
        requireNotNull(asset)

        val directory = storage.extractedDir(asset)
        assertThat(ModelArchive.unpack(storage.target(asset), directory)).isTrue()
        // Unpacking again must be a no-op rather than a second several-second wait.
        assertThat(ModelArchive.unpack(storage.target(asset), directory)).isTrue()

        val engine = PiperEngine()
        try {
            assertThat(runBlocking { engine.load(directory) }).isTrue()

            // Spoken rather than only synthesised: this is the one test anybody nearby
            // can check the result of by listening.
            val spoken = runBlocking { engine.speak("Alarm set for seven in the morning.") }
            Log.i("TheMachine", "PIPER spoke=$spoken")
            assertThat(spoken).isTrue()
        } finally {
            engine.release()
        }
    }

    @Test
    fun nothingIsSpokenWithoutAVoice() {
        // A phone with no voice installed still has to work, silently.
        val engine = PiperEngine()
        assertThat(engine.isLoaded).isFalse()
        assertThat(runBlocking { engine.speak("this should not throw") }).isFalse()
        engine.release()
    }
}
