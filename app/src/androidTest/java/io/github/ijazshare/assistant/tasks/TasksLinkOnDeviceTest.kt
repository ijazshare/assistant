/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.tasks

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real link, over the real binder, to the Tasks app installed on the phone (same build type: debug to debug).
 * Everything here only reads, except [migration], which is the real migration of tasks.md and does nothing unless
 * asked: `-e runMigration true`. Run it with `adb shell am instrument`, never connectedAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class TasksLinkOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val client = TaskAppClient.of(context)

    @Test
    fun theCallerIsTrustedAndStatusAnswers() = runBlocking<Unit> {
        assumeTrue(client.isInstalled())
        val answer = client.send(TaskRequest("status"))
        Log.i(TAG, "status $answer")
        assertThat(answer.status).isEqualTo("done")
        assertThat(answer.health).isNotNull()
    }

    @Test
    fun aQueryAnswersAndTheWeatherIsNotMine() = runBlocking<Unit> {
        assumeTrue(client.isInstalled())
        assertThat(client.send(TaskRequest("utterance", "what's on my list today")).status).isEqualTo("done")
        assertThat(client.send(TaskRequest("utterance", "what's the weather")).status).isEqualTo("not_mine")
    }

    @Test
    fun readingStaysInsideTheLatencyBudget() = runBlocking<Unit> {
        assumeTrue(client.isInstalled())
        client.send(TaskRequest("status"))
        val millis = List(SAMPLES) {
            val t0 = System.nanoTime()
            client.send(TaskRequest("utterance", "what's on my list today"))
            (System.nanoTime() - t0) / NANOS_PER_MS
        }.sorted()
        Log.i(
            TAG,
            "binder round trip ms p50=${millis[SAMPLES / 2]} p95=${millis[SAMPLES * 95 / 100]} max=${millis.last()}",
        )
        assertThat(millis[SAMPLES * 95 / 100]).isLessThan(MAX_P95_MILLIS)
    }

    @Test
    fun migration() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runMigration") == "true")
        Log.i(TAG, "migration: ${TaskMigration(context).run()}")
    }

    private companion object {
        const val TAG = "TasksLink"
        const val SAMPLES = 40
        const val NANOS_PER_MS = 1_000_000L
        const val MAX_P95_MILLIS = 150L
    }
}
