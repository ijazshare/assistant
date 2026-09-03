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

/**
 * Which screen requests are questions and which are recitals.
 *
 * The failure this exists to prevent is from the user's own history: "Read the screen and
 * summarize it" spoke a raw accessibility dump of somebody's chat log.
 */
class ScreenQuestionTest {

    @Test
    fun `summarising is a question`() {
        assertThat(ScreenQuestion.of("Read the screen and summarize it."))
            .isEqualTo("Read the screen and summarize it.")
        assertThat(ScreenQuestion.of("summarise this")).isNotNull()
        assertThat(ScreenQuestion.of("give me a summary")).isNotNull()
    }

    @Test
    fun `plain reading is not a question`() {
        // These must stay cheap: sending them to the larger model would cost a load and
        // several seconds to produce something worse than the words themselves.
        assertThat(ScreenQuestion.of("what does this say")).isNull()
        assertThat(ScreenQuestion.of("read this to me")).isNull()
        assertThat(ScreenQuestion.of("read the screen")).isNull()
    }

    @Test
    fun `asking what is wrong is a question`() {
        assertThat(ScreenQuestion.of("what does this error mean")).isNotNull()
        assertThat(ScreenQuestion.of("what is wrong with this")).isNotNull()
        assertThat(ScreenQuestion.of("translate this")).isNotNull()
    }

    @Test
    fun `the model's own argument still counts`() {
        // If the small model did fill the parameter, that is corroboration and the
        // request is treated as a question even when the wording alone would not.
        assertThat(ScreenQuestion.of("what is on screen", modelArgument = "who sent this"))
            .isEqualTo("what is on screen")
    }

    @Test
    fun `an empty argument is not corroboration`() {
        assertThat(ScreenQuestion.of("read this", modelArgument = "   ")).isNull()
    }
}
