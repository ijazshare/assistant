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
import io.github.hasanismail.themachine.tools.MachineTools
import io.github.hasanismail.themachine.tools.ToolCall
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CommandCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun cache() = CommandCache(File(folder.root, "cache.json"))

    private val timer = ToolCall(MachineTools.SET_TIMER, mapOf("minutes" to "10"))

    @Test
    fun `a learned phrase is found again, however it is spelt`() {
        val cache = cache()
        assertThat(cache.learn("Set a timer for 10 minutes.", timer)).isTrue()
        assertThat(cache.lookup("timer 10 mins")).isEqualTo(timer)
        assertThat(cache.lookup("please set a timer for ten minutes")).isEqualTo(timer)
    }

    @Test
    fun `what one instance learns another reads from disk`() {
        cache().learn("open spotify", ToolCall(MachineTools.OPEN_APP, mapOf("app" to "Spotify")))
        assertThat(cache().lookup("Open Spotify.")?.tool).isEqualTo(MachineTools.OPEN_APP)
    }

    @Test
    fun `an unknown phrase is a miss`() {
        assertThat(cache().lookup("set a timer for 10 minutes")).isNull()
    }

    @Test
    fun `answers are never cached`() {
        // The reply depends on the user's notes and the date, neither of which is in the words.
        val cache = cache()
        assertThat(cache.learn("what is my brother called", ToolCall(MachineTools.ANSWER, mapOf("text" to "Osman."))))
            .isFalse()
        assertThat(cache.lookup("what is my brother called")).isNull()
    }

    @Test
    fun `unsupported and messaging are never cached`() {
        val cache = cache()
        assertThat(cache.learn("book me a flight", ToolCall(MachineTools.UNSUPPORTED, emptyMap()))).isFalse()
        assertThat(
            cache.learn(
                "text Ali I am late",
                ToolCall(MachineTools.SEND_MESSAGE, mapOf("recipient" to "Ali", "body" to "I am late")),
            ),
        ).isFalse()
    }

    @Test
    fun `a phrase that depends on the clock is never cached`() {
        val cache = cache()
        val call = ToolCall(MachineTools.CREATE_REMINDER, mapOf("task" to "call Ali", "hour" to "15"))
        assertThat(cache.learn("remind me to call Ali in an hour", call)).isFalse()
        assertThat(cache.lookup("remind me to call Ali in an hour")).isNull()
    }

    @Test
    fun `a number the model produced must have been said`() {
        val cache = cache()
        // Right answer, but 30 is not in the words: indistinguishable from a guess.
        assertThat(
            cache.learn(
                "wake me at half past six",
                ToolCall(MachineTools.SET_ALARM, mapOf("hour" to "6", "minute" to "30")),
            ),
        ).isFalse()
        // The same time said with digits can be checked, and is kept.
        assertThat(
            cache.learn(
                "wake me at 6:30",
                ToolCall(MachineTools.SET_ALARM, mapOf("hour" to "6", "minute" to "30")),
            ),
        ).isTrue()
        // An evening hour in 24-hour form is backed by its 12-hour spoken form.
        assertThat(
            cache.learn(
                "alarm for 6pm",
                ToolCall(MachineTools.SET_ALARM, mapOf("hour" to "18", "minute" to "0")),
            ),
        ).isTrue()
    }

    @Test
    fun `a minute the model dropped is not frozen`() {
        val cache = cache()
        // "6:30" with the model answering minute 0: the alarm is wrong, and caching it
        // would make it wrong every morning after.
        assertThat(
            cache.learn("alarm for 6:30", ToolCall(MachineTools.SET_ALARM, mapOf("hour" to "6", "minute" to "0"))),
        ).isFalse()
        // The same phrase read correctly is fine.
        assertThat(
            cache.learn("alarm for 6:30", ToolCall(MachineTools.SET_ALARM, mapOf("hour" to "6", "minute" to "30"))),
        ).isTrue()
    }

    @Test
    fun `a phrase with no clock in it is learned even if it contains a veto word`() {
        // "this" is a relative-time word, but "read this" has no time in it at all and
        // was permanently barred from the fast path.
        assertThat(cache().learn("read this", ToolCall(MachineTools.READ_SCREEN, emptyMap()))).isTrue()
        assertThat(cache().learn("open spotify now", ToolCall(MachineTools.OPEN_APP, mapOf("app" to "Spotify"))))
            .isTrue()
    }

    @Test
    fun `an entry naming a tool that may not be cached is ignored on load`() {
        val file = File(folder.root, "cache.json")
        file.writeText(
            """{"entries":[{"key":"open spotify","tool":"send_message",""" +
                """"arguments":{"recipient":"Ali","body":"hi"},"lastUsedEpochMillis":0}]}""",
        )
        assertThat(CommandCache(file).lookup("open Spotify")).isNull()
    }

    @Test
    fun `a task rewritten through the user's notes is not cached`() {
        val cache = cache()
        // "my brother" became "Osman" via memories.md; that depends on the notes, not the words.
        assertThat(
            cache.learn(
                "remind me to call my brother",
                ToolCall(MachineTools.CREATE_REMINDER, mapOf("task" to "call Osman")),
            ),
        ).isFalse()
        assertThat(
            cache.learn(
                "remind me to buy milk",
                ToolCall(MachineTools.CREATE_REMINDER, mapOf("task" to "buy milk")),
            ),
        ).isTrue()
    }

    @Test
    fun `the tomorrow flag must match the word`() {
        val cache = cache()
        assertThat(
            cache.learn(
                "remind me tomorrow at 7 to call Ali",
                ToolCall(
                    MachineTools.CREATE_REMINDER,
                    mapOf("task" to "call Ali", "hour" to "7", "tomorrow" to "true"),
                ),
            ),
        ).isTrue()
        assertThat(
            cache.learn(
                "remind me at 7 to call Ali",
                ToolCall(
                    MachineTools.CREATE_REMINDER,
                    mapOf("task" to "call Ali", "hour" to "7", "tomorrow" to "true"),
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `an app name the model corrected is not cached`() {
        val cache = cache()
        // Whisper heard "spot a fee"; the model knew what was meant. Leave that to the model.
        assertThat(cache.learn("open spot a fee", ToolCall(MachineTools.OPEN_APP, mapOf("app" to "Spotify"))))
            .isFalse()
        assertThat(cache.learn("open the camera", ToolCall(MachineTools.OPEN_APP, mapOf("app" to "Camera"))))
            .isTrue()
    }

    @Test
    fun `tapping by label is never cached`() {
        assertThat(cache().learn("tap log in", ToolCall(MachineTools.TAP_TEXT, mapOf("label" to "Log in"))))
            .isFalse()
    }

    @Test
    fun `a single word is never cached`() {
        assertThat(cache().learn("timer", timer)).isFalse()
    }

    @Test
    fun `hits are counted and the newest use is kept`() {
        val cache = cache()
        cache.learn("timer 10 minutes", timer, now = 1_000)
        cache.lookup("timer 10 minutes", now = 2_000)
        cache.lookup("timer 10 minutes", now = 3_000)
        val entry = cache.all().single()
        assertThat(entry.hits).isEqualTo(2)
        assertThat(entry.lastUsedEpochMillis).isEqualTo(3_000)
    }

    @Test
    fun `the least recently used phrase is evicted first`() {
        val cache = cache()
        for (i in 0 until CommandCache.MAX_ENTRIES) {
            cache.learn(
                "timer $i minutes",
                ToolCall(MachineTools.SET_TIMER, mapOf("minutes" to "$i")),
                now = i.toLong(),
            )
        }
        assertThat(cache.size).isEqualTo(CommandCache.MAX_ENTRIES)
        cache.learn("open spotify", ToolCall(MachineTools.OPEN_APP, mapOf("app" to "Spotify")), now = 1_000_000)
        assertThat(cache.size).isEqualTo(CommandCache.MAX_ENTRIES)
        assertThat(cache.lookup("timer 0 minutes")).isNull()
        assertThat(cache.lookup("open spotify")).isNotNull()
    }

    @Test
    fun `a corrupt file means an empty cache, not a crash`() {
        val file = File(folder.root, "cache.json")
        file.writeText("{ this is not json")
        val cache = CommandCache(file)
        assertThat(cache.size).isEqualTo(0)
        assertThat(cache.learn("timer 10 minutes", timer)).isTrue()
    }

    @Test
    fun `a phrase can be forgotten`() {
        val cache = cache()
        cache.learn("timer 10 minutes", timer)
        cache.forget("Timer, 10 minutes")
        assertThat(cache.lookup("timer 10 minutes")).isNull()
    }
}
