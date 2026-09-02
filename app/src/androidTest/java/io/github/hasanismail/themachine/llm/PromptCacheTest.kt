/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.llm

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.tools.MachineTools
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The prompt cache, which is the difference between the first command of a session
 * costing what the second one does and costing ten seconds more.
 */
@RunWith(AndroidJUnit4::class)
class PromptCacheTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = ModelStorage(context)

    private fun model() = ModelRegistry(context).byRole(ModelRole.LLM)
        .filter { storage.quickState(it) == ModelState.Ready }
        .let { ready -> ready.firstOrNull { it.isDefault } ?: ready.firstOrNull() }

    private fun ask(engine: LlamaEngine, utterance: String): Long {
        val dialect = PromptDialect.forModel(model()!!.fileName)
        val completion = runBlocking {
            engine.generate(
                dialect.buildPrompt(utterance, MachineTools.all, "Hasan", ""),
                dialect.grammar(MachineTools.all),
            )
        }
        assertThat(dialect.parse(completion.text)).isNotNull()
        return completion.millis
    }

    @Test
    fun aSavedCacheMakesTheFirstCommandAsQuickAsTheSecond() {
        val asset = model()
        assumeTrue("No language model installed", asset != null)
        requireNotNull(asset)
        val file = storage.target(asset)
        val cache = File(file.parentFile, file.name + LlamaEngine.CACHE_SUFFIX)

        // A genuinely cold start: no cache on disk, nothing prefilled.
        cache.delete()
        val cold = LlamaEngine(context)
        assertThat(runBlocking { cold.load(file) }).isTrue()
        val coldMillis = ask(cold, "set an alarm for seven")
        assertThat(runBlocking { cold.saveState() }).isTrue()
        cold.unload()

        assertThat(cache.isFile).isTrue()
        Log.i(TAG, "CACHE ${cache.length() / 1024} KiB, cold reply $coldMillis ms")

        // A second engine, loading that cache, answering a different question.
        val warm = LlamaEngine(context)
        assertThat(runBlocking { warm.load(file) }).isTrue()
        val warmMillis = ask(warm, "set a timer for five minutes")
        warm.unload()
        Log.i(TAG, "CACHE warm reply $warmMillis ms")

        // Halving is a deliberately loose bar. On a cool phone the gap is far larger,
        // but this runs on whatever thermal state the previous tests left behind, and a
        // flaky performance assertion is worse than a lenient one.
        assertThat(warmMillis).isLessThan(coldMillis / 2)
    }

    @Test
    fun anUnreadableCacheIsIgnoredRatherThanFatal() {
        val asset = model()
        assumeTrue("No language model installed", asset != null)
        requireNotNull(asset)
        val file = storage.target(asset)
        val cache = File(file.parentFile, file.name + LlamaEngine.CACHE_SUFFIX)

        // What a half-written or stale file looks like. It must cost a prefill, not a
        // crash: this is the one failure the user would otherwise meet as a dead button.
        cache.writeBytes(ByteArray(NONSENSE_BYTES) { it.toByte() })
        val engine = LlamaEngine(context)
        try {
            assertThat(runBlocking { engine.load(file) }).isTrue()
            assertThat(ask(engine, "open Spotify")).isGreaterThan(0L)
        } finally {
            engine.unload()
            cache.delete()
        }
    }

    private companion object {
        const val TAG = "TheMachine"
        const val NONSENSE_BYTES = 4096
    }
}
