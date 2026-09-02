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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.hasanismail.themachine.history.QueryLog
import io.github.hasanismail.themachine.history.QuerySource
import io.github.hasanismail.themachine.history.Resolution
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.tools.MachineTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole session driven by typed text: no microphone, everything else real.
 *
 * Also the one test of the learned-phrase cache that goes through the session rather
 * than the cache alone. The first command is resolved by the model and remembered; the
 * second, worded differently, must come back from the cache with the model uninvolved.
 * It sets a real one-minute timer on the phone, twice, which is the point.
 */
@RunWith(AndroidJUnit4::class)
class TypedCommandTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var session: VoiceSession

    private fun hasModel() = ModelRegistry(context).byRole(ModelRole.LLM)
        .any { ModelStorage(context).quickState(it) == ModelState.Ready }

    @Before
    fun freshStart() {
        // A clean slate, so the first command is a genuine model resolution.
        CommandCache.shared(File(context.getExternalFilesDir(null), CommandCache.FILE_NAME)).clear()
        QueryLog(context).clear()
        session = VoiceSession(context, scope)
    }

    @After
    fun tearDown() {
        session.release()
        scope.cancel()
    }

    /** Submits a command and waits for it to finish, one way or the other. */
    private fun run(text: String): SessionState = runBlocking {
        session.submitText(text)
        withTimeout(TIMEOUT_MILLIS) {
            session.state
                .dropWhile { it is SessionState.Done || it is SessionState.Problem }
                .first { it is SessionState.Done || it is SessionState.Problem }
        }
    }

    @Test
    fun aTypedCommandIsResolvedThenLearnedThenInstant() {
        assumeTrue("No language model installed", hasModel())

        val first = run("set a timer for 1 minute")
        Log.i(TAG, "TYPED first -> $first")
        assertThat(first).isInstanceOf(SessionState.Done::class.java)
        first as SessionState.Done
        assertThat(first.tool).isEqualTo(MachineTools.SET_TIMER)
        assertThat(first.fromCache).isFalse()
        assertThat(first.timing.llmMillis).isGreaterThan(0L)

        // Different words, same command. No model this time.
        val second = run("timer 1 min")
        Log.i(TAG, "TYPED second -> $second")
        assertThat(second).isInstanceOf(SessionState.Done::class.java)
        second as SessionState.Done
        assertThat(second.tool).isEqualTo(MachineTools.SET_TIMER)
        assertThat(second.fromCache).isTrue()
        assertThat(second.timing.llmMillis).isEqualTo(0L)
        assertThat(second.result.spoken).isEqualTo(first.result.spoken)

        // Both are on the record, newest first, each saying how it was resolved.
        val history = QueryLog(context).recent()
        assertThat(history).hasSize(2)
        assertThat(history[0].resolution).isEqualTo(Resolution.CACHE)
        assertThat(history[1].resolution).isEqualTo(Resolution.MODEL)
        assertThat(history.map { it.source }.toSet()).containsExactly(QuerySource.TYPED)
    }

    @Test
    fun aQuestionIsAnsweredButNotLearned() {
        assumeTrue("No language model installed", hasModel())

        val state = run("who painted the Mona Lisa")
        assertThat(state).isInstanceOf(SessionState.Done::class.java)
        state as SessionState.Done
        // Whatever it chose, the reply depends on more than the words and is never cached.
        val cache = CommandCache.shared(File(context.getExternalFilesDir(null), CommandCache.FILE_NAME))
        assertThat(cache.lookup("who painted the Mona Lisa")).isNull()
    }

    private companion object {
        const val TAG = "TheMachine"
        const val TIMEOUT_MILLIS = 90_000L
    }
}
