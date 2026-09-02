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

class CommandKeyTest {

    @Test
    fun `whisper punctuation and typed shorthand meet in the middle`() {
        // What Whisper writes, what a person types, and what someone shouts.
        assertThat(CommandKey.of("Set a timer for 10 minutes.")).isEqualTo("timer 10 minutes")
        assertThat(CommandKey.of("timer 10 mins")).isEqualTo("timer 10 minutes")
        assertThat(CommandKey.of("TIMER, 10 MINS!")).isEqualTo("timer 10 minutes")
        assertThat(CommandKey.of("please set a timer for ten minutes")).isEqualTo("timer 10 minutes")
    }

    @Test
    fun `word order is kept`() {
        // "ten minute timer" and "timer ten minutes" are the same command, but the cache
        // is allowed to learn them separately: exactness is the whole point of it, and a
        // second entry costs one more round through the model, once.
        assertThat(CommandKey.of("Ten minute timer")).isEqualTo("10 minutes timer")
    }

    @Test
    fun `compound numbers become one number`() {
        assertThat(CommandKey.of("wake me at twenty five past seven")).isEqualTo("wake at 25 past 7")
        assertThat(CommandKey.of("timer for forty five seconds")).isEqualTo("timer 45 seconds")
        assertThat(CommandKey.of("alarm at six thirty")).isEqualTo("alarm at 6 30")
    }

    @Test
    fun `clock times keep their colon`() {
        assertThat(CommandKey.of("alarm for 6:30")).isEqualTo("alarm 6:30")
        // A colon that is not between digits is punctuation.
        assertThat(CommandKey.of("timer: ten minutes")).isEqualTo("timer 10 minutes")
    }

    @Test
    fun `politeness and filler are dropped`() {
        assertThat(CommandKey.of("hey can you open Spotify please")).isEqualTo("open spotify")
        assertThat(CommandKey.of("Open Spotify.")).isEqualTo("open spotify")
    }

    @Test
    fun `nothing meaningful gives no key`() {
        assertThat(CommandKey.of("")).isNull()
        assertThat(CommandKey.of("...")).isNull()
        assertThat(CommandKey.of("please")).isNull()
    }

    @Test
    fun `phrases that depend on the clock are recognised`() {
        // Cached as an absolute hour, these would fire at the wrong time tomorrow.
        assertThat(CommandKey.isTimeRelative("remind me in an hour")).isTrue()
        assertThat(CommandKey.isTimeRelative("remind me to call mom this evening")).isTrue()
        assertThat(CommandKey.isTimeRelative("set an alarm for later today")).isTrue()
        assertThat(CommandKey.isTimeRelative("wake me at 7 tonight")).isTrue()
    }

    @Test
    fun `phrases whose meaning is fixed are not`() {
        assertThat(CommandKey.isTimeRelative("set a timer for ten minutes")).isFalse()
        assertThat(CommandKey.isTimeRelative("what did I miss")).isFalse()
        assertThat(CommandKey.isTimeRelative("set an alarm for 7am")).isFalse()
        // "tomorrow" reaches the tool as a flag, so it means the same thing on any day.
        assertThat(CommandKey.isTimeRelative("remind me tomorrow at 7 to call Ali")).isFalse()
    }
}
