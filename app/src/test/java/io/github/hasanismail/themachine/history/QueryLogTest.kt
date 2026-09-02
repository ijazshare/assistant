/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.history

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QueryLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun log() = QueryLog(File(folder.root, "queries.jsonl"))

    private fun record(n: Int) = QueryRecord(
        atEpochMillis = n.toLong(),
        transcript = "command $n",
        source = QuerySource.VOICE,
        resolution = Resolution.MODEL,
        tool = "set_timer",
        arguments = mapOf("minutes" to "$n"),
        spoken = "Timer set.",
        success = true,
        sttMillis = 300,
        llmMillis = 700,
    )

    @Test
    fun `records come back newest first with every field intact`() {
        val log = log()
        log.append(record(1))
        log.append(record(2))
        val recent = log.recent()
        assertThat(recent.map { it.transcript }).containsExactly("command 2", "command 1").inOrder()
        assertThat(recent[0].arguments).containsEntry("minutes", "2")
        assertThat(recent[0].llmMillis).isEqualTo(700)
    }

    @Test
    fun `the file is trimmed once it is well past its bound`() {
        val log = log()
        val total = QueryLog.KEEP + QueryLog.TRIM_SLACK + 1
        for (n in 1..total) log.append(record(n))
        val recent = log.recent(limit = Int.MAX_VALUE)
        assertThat(recent).hasSize(QueryLog.KEEP)
        // The oldest were the ones dropped.
        assertThat(recent.last().transcript).isEqualTo("command ${total - QueryLog.KEEP + 1}")
        assertThat(recent.first().transcript).isEqualTo("command $total")
    }

    @Test
    fun `a damaged line is skipped, not fatal`() {
        val file = File(folder.root, "queries.jsonl")
        val log = QueryLog(file)
        log.append(record(1))
        file.appendText("this line is not json\n")
        log.append(record(2))
        assertThat(log.recent().map { it.transcript }).containsExactly("command 2", "command 1").inOrder()
    }

    @Test
    fun `clearing leaves nothing`() {
        val log = log()
        log.append(record(1))
        log.clear()
        assertThat(log.recent()).isEmpty()
    }
}
