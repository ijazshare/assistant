/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.context

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class MachineFilesTaskTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun files() = MachineFiles(File(folder.root, "context"))

    private val due = LocalDateTime.of(2026, 9, 2, 19, 0)

    @Test
    fun `a task written by the assistant can be read back by its id`() {
        val files = files()
        val id = files.appendTask("call Ali", due)
        val task = files.readTasks().single()
        assertThat(task.id).isEqualTo(id)
        assertThat(task.title).isEqualTo("call Ali")
        assertThat(task.due).isEqualTo(due)
        assertThat(task.done).isFalse()
    }

    @Test
    fun `two tasks get two ids`() {
        val files = files()
        val first = files.appendTask("buy milk", due)
        val second = files.appendTask("buy bread", due)
        assertThat(first).isNotEqualTo(second)
        assertThat(files.readTasks()).hasSize(2)
    }

    @Test
    fun `a line the user wrote by hand still parses`() {
        val files = files()
        files.write(MachineFiles.TASKS, "# Tasks\n\n- [ ] water the plants\n- [x] pay rent\n")
        val tasks = files.readTasks()
        assertThat(tasks.map { it.title }).containsExactly("water the plants", "pay rent").inOrder()
        assertThat(tasks.map { it.id }).containsExactly(null, null)
        assertThat(tasks.map { it.done }).containsExactly(false, true).inOrder()
    }

    @Test
    fun `the template's own lines are not tasks`() {
        val files = files()
        files.appendTask("buy milk", null)
        assertThat(files.readTasks()).hasSize(1)
    }

    @Test
    fun `completing ticks the box and leaves everything else alone`() {
        val files = files()
        val id = files.appendTask("call Ali", due)
        files.appendTask("buy milk", null)
        assertThat(files.completeTask(id)).isTrue()
        val tasks = files.readTasks()
        assertThat(tasks[0].done).isTrue()
        assertThat(tasks[0].id).isEqualTo(id)
        assertThat(tasks[1].done).isFalse()
        assertThat(files.completeTask("nope")).isFalse()
    }

    @Test
    fun `rescheduling moves both the words and the field`() {
        val files = files()
        val id = files.appendTask("call Ali", due)
        val later = due.plusMinutes(10)
        assertThat(files.rescheduleTask(id, later)).isTrue()
        val task = files.readTasks().single()
        assertThat(task.due).isEqualTo(later)
        assertThat(task.title).isEqualTo("call Ali")
        assertThat(files.read(MachineFiles.TASKS)).contains("7:10")
    }

    @Test
    fun `a task with no time can be given one`() {
        val files = files()
        val id = files.appendTask("call Ali", null)
        assertThat(files.rescheduleTask(id, due)).isTrue()
        assertThat(files.readTasks().single().due).isEqualTo(due)
    }

    @Test
    fun `the model never sees the bookkeeping`() {
        val files = files()
        val id = files.appendTask("call Ali", due)
        val prompt = files.contextForPrompt()
        assertThat(prompt).contains("call Ali")
        assertThat(prompt).doesNotContain(id)
        assertThat(prompt).doesNotContain("<!--")
    }
}
