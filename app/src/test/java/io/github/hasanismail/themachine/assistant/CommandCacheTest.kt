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
            cache.learn("timer $i minutes", timer, now = i.toLong())
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
