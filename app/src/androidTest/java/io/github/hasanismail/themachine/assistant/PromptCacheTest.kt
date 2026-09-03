/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The prompt cache has to survive the session that wrote it.
 *
 * It was written on the overlay's own scope, which is cancelled the moment the panel
 * closes — about two seconds after the reply is spoken. The write never finished, no
 * file was ever left on disk, and every first command of every session paid a full
 * prefill for a prefix that had already been computed dozens of times. The bug is
 * invisible from inside a session, which is why it survived: reuse within one process
 * worked perfectly the whole time.
 */
@RunWith(AndroidJUnit4::class)
class PromptCacheTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Chosen exactly as the session chooses, so the file under test is the one it writes. */
    private fun modelFile(): File? {
        val storage = ModelStorage(context)
        val ready = ModelRegistry(context).byRole(ModelRole.LLM)
            .filter { storage.quickState(it) == ModelState.Ready }
        val asset = ready.firstOrNull { it.isDefault } ?: ready.firstOrNull()
        return asset?.let { storage.target(it) }
    }

    @Test
    fun theCacheIsOnDiskAfterTheSessionCloses() {
        val model = modelFile()
        assumeTrue("No language model installed", model != null)
        val cacheFile = File(model!!.parentFile, model.name + ".prompt-cache")
        cacheFile.delete()
        // Without this the command comes back from the learned-phrase cache, the model is
        // never loaded, and there is correctly nothing to save — which looks identical to
        // the bug from the outside. It cost a test run to notice.
        CommandCache.shared(File(context.getExternalFilesDir(null), CommandCache.FILE_NAME)).clear()

        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val session = VoiceSession(context, scope)
        val done = runBlocking {
            session.submitText("set a timer for 1 minute")
            withTimeout(TIMEOUT_MILLIS) {
                session.state
                    .dropWhile { it is SessionState.Done || it is SessionState.Problem }
                    .first { it is SessionState.Done || it is SessionState.Problem }
            }
        }
        assertThat(done).isInstanceOf(SessionState.Done::class.java)
        assertThat((done as SessionState.Done).fromCache).isFalse()

        // Exactly what happens on the device: the panel goes away, taking its scope with
        // it, and only then is the session released.
        scope.cancel()
        session.release()

        // release() hands the write and the unload to a scope that outlives both, so the
        // file appears shortly after, not instantly.
        val deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MILLIS
        while (cacheFile.length() == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        assertThat(cacheFile.length()).isGreaterThan(0L)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 90_000L
        const val WRITE_TIMEOUT_MILLIS = 30_000L
        const val POLL_MILLIS = 250L
    }
}
