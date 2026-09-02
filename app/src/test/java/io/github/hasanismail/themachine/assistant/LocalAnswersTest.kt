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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

class LocalAnswersTest {

    private val now = LocalDateTime.of(2026, 9, 2, 14, 5)

    @Test
    fun `the clock answers for the clock`() {
        // The exact question that came back reading out the notification shade.
        assertThat(LocalAnswers.of("what time is it", now)).isEqualTo("It is 2:05 PM.")
        assertThat(LocalAnswers.of("What's the time?", now)).isEqualTo("It is 2:05 PM.")
        assertThat(LocalAnswers.of("tell me the time", now)).isEqualTo("It is 2:05 PM.")
    }

    @Test
    fun `and for the date`() {
        assertThat(LocalAnswers.of("what is the date", now)).isEqualTo("It is Wednesday, 2 September.")
        assertThat(LocalAnswers.of("what day is it", now)).isEqualTo("It is Wednesday, 2 September.")
    }

    @Test
    fun `both when both are asked`() {
        assertThat(LocalAnswers.of("what is the time and date", now))
            .isEqualTo("It is 2:05 PM on Wednesday, 2 September.")
    }

    @Test
    fun `a command that merely mentions time is not a question`() {
        // These must reach the model: answering them from the clock would be worse than
        // useless, it would stop them working.
        assertThat(LocalAnswers.of("set a timer for ten minutes", now)).isNull()
        assertThat(LocalAnswers.of("what time does the shop close", now)).isNull()
        assertThat(LocalAnswers.of("remind me to call Ali today", now)).isNull()
        assertThat(LocalAnswers.of("what did I miss", now)).isNull()
        assertThat(LocalAnswers.of("read the screen", now)).isNull()
    }

    @Test
    fun `nothing at all is not a question`() {
        assertThat(LocalAnswers.of("", now)).isNull()
        assertThat(LocalAnswers.of("time", now)).isNull()
    }
}
