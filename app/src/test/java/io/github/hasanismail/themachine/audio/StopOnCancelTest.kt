/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.audio

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** A latch stands in for AudioRecord.read: it blocks a thread, and only stop() lets it go. */
class StopOnCancelTest {

    @Test
    fun `a cancel unblocks a coroutine stuck in a call that only stop ends`() = runBlocking {
        val read = CountDownLatch(1)
        val inRead = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            val watcher = stopOnCancel { read.countDown() }
            try {
                inRead.countDown()
                read.await()
            } finally {
                watcher.cancel()
            }
        }
        inRead.await()

        val joined = withTimeoutOrNull(TIMEOUT_MILLIS) { job.cancelAndJoin() }

        assertThat(joined).isNotNull()
        assertThat(read.count).isEqualTo(0)
    }

    @Test
    fun `invokeOnCompletion alone does not, which is why it is not used`() = runBlocking {
        val read = CountDownLatch(1)
        val inRead = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            coroutineContext.job.invokeOnCompletion { read.countDown() }
            inRead.countDown()
            read.await()
        }
        inRead.await()

        val joined = withTimeoutOrNull(SHORT_MILLIS) { job.cancelAndJoin() }

        assertThat(joined).isNull()
        read.countDown() // let the thread go
        job.join()
    }

    @Test
    fun `a capture that ends by itself does not call stop`() = runBlocking {
        val stops = AtomicInteger()
        val job = launch(Dispatchers.IO) {
            stopOnCancel { stops.incrementAndGet() }.cancelAndJoin()
        }
        job.join()

        assertThat(job.isCancelled).isFalse()
        assertThat(stops.get()).isEqualTo(0)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val SHORT_MILLIS = 400L
    }
}
