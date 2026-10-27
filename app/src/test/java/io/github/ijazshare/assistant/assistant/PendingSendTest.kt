/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PendingSendTest {

    private val asked = PendingSend(number = "+15550100", display = "Sam Lee", askedAtMillis = 1_000_000L)

    @Test
    fun `an answer within a minute is still an answer`() {
        assertThat(asked.expired(1_000_000L + 59_000L)).isFalse()
        assertThat(asked.expired(1_000_000L + PendingSend.WINDOW_MILLIS)).isFalse()
    }

    @Test
    fun `a transcript after a minute is a new command`() {
        assertThat(asked.expired(1_000_000L + PendingSend.WINDOW_MILLIS + 1)).isTrue()
    }

    @Test
    fun `a change of mind is not a message`() {
        listOf("cancel", "Cancel.", "never mind", "Never mind.", "nevermind", "Stop", "forget it", "Forget it!")
            .forEach { assertThat(PendingSend.isCancel(it)).isTrue() }
        assertThat(PendingSend.isCancel("Don't send it.")).isTrue()
        assertThat(PendingSend.isCancel("don’t send it")).isTrue()
    }

    @Test
    fun `a message that merely contains a cancel word is a message`() {
        assertThat(PendingSend.isCancel("Cancel my appointment tomorrow")).isFalse()
        assertThat(PendingSend.isCancel("I'll be late, never mind dinner")).isFalse()
        assertThat(PendingSend.isCancel("stop by the shop on your way")).isFalse()
    }
}
