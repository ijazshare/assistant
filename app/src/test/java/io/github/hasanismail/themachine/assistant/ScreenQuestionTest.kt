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

    @Test
    fun `requests about the screen point at it`() {
        assertThat(ScreenQuestion.pointsAtScreen("what does this say")).isTrue()
        assertThat(ScreenQuestion.pointsAtScreen("Read out what's on my screen.")).isTrue()
        assertThat(ScreenQuestion.pointsAtScreen("How much is the total on this receipt?")).isTrue()
        assertThat(ScreenQuestion.pointsAtScreen("what does it say")).isTrue()
        // A mishearing still carries the verb.
        assertThat(ScreenQuestion.pointsAtScreen("Read what's on the scream.")).isTrue()
        // Nothing but the screen can be summarised or translated.
        assertThat(ScreenQuestion.pointsAtScreen("summarize it")).isTrue()
        assertThat(ScreenQuestion.pointsAtScreen("give me a summary")).isTrue()
        assertThat(ScreenQuestion.pointsAtScreen("Translate that.")).isTrue()
    }

    @Test
    fun `questions and overheard speech do not`() {
        // The 4B sent all of these to read_screen.
        assertThat(ScreenQuestion.pointsAtScreen("What's the week's forecast?")).isFalse()
        assertThat(ScreenQuestion.pointsAtScreen("What is the traffic like right now?")).isFalse()
        assertThat(
            ScreenQuestion.pointsAtScreen("The next train to Manchester Piccadilly will depart from platform 2."),
        ).isFalse()
        assertThat(ScreenQuestion.pointsAtScreen("Thanks for watching, and I'll see you in the next one.")).isFalse()
        assertThat(ScreenQuestion.pointsAtScreen("Could you explain the meaning of exorbitant?")).isFalse()
    }
}
