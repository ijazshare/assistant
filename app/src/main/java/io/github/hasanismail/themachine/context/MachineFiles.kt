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

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One of the Markdown files that make up what the assistant knows about you. */
data class MachineFile(
    val id: String,
    val fileName: String,
    val title: String,
    val purpose: String,
    /** What a new install starts with — an example, not an empty page. */
    val template: String,
)

/**
 * The assistant's memory, as plain Markdown files on disk.
 *
 * Markdown rather than a database for three reasons that all matter here. The model
 * reads them, and Markdown is what it was trained on. The user can open, edit and
 * back them up with any text editor, which is the honest form for "what this thing
 * knows about me". And when the assistant records something, the record is legible
 * afterwards instead of being a row nobody can see.
 *
 * They live in app-private storage: no permission needed, and they leave with the app.
 */
class MachineFiles(private val context: Context) {

    private val root: File
        get() = File(context.getExternalFilesDir(null), "context").apply { mkdirs() }

    fun file(spec: MachineFile): File = File(root, spec.fileName)

    /** Reads a file, writing the template first if it does not exist yet. */
    fun read(spec: MachineFile): String {
        val target = file(spec)
        if (!target.exists()) target.writeText(spec.template)
        return target.readText()
    }

    fun write(spec: MachineFile, content: String) {
        file(spec).writeText(content)
    }

    /** True once the user has actually put something of their own in it. */
    fun isCustomised(spec: MachineFile): Boolean =
        file(spec).let { it.exists() && it.readText().trim() != spec.template.trim() }

    /**
     * Appends a task. Written by the assistant, but in the same file and the same
     * format the user edits by hand — there is no separate machine-only store.
     */
    fun appendTask(task: String, due: LocalDateTime?) {
        val target = file(TASKS)
        if (!target.exists()) target.writeText(TASKS.template)
        val stamp = LocalDateTime.now().format(STAMP)
        val dueText = due?.let { " — due ${it.format(DUE)}" } ?: ""
        target.appendText("\n- [ ] $task$dueText  <!-- added $stamp -->")
    }

    /** Everything the model should be told, concatenated in a stable order. */
    fun contextForPrompt(): String = ALL
        .filter { isCustomised(it) || it.id == TASKS.id }
        .joinToString("\n\n") { spec ->
            "## ${spec.title}\n" + read(spec).lineSequence()
                // Strip the guidance comments; they are for the user, not the model.
                .filterNot { it.trimStart().startsWith("<!--") || it.trimStart().startsWith(">") }
                .joinToString("\n")
                .trim()
        }
        .trim()

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val DUE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")

        val PROFILE = MachineFile(
            id = "profile",
            fileName = "profile.md",
            title = "About the admin",
            purpose = "Who you are. The assistant reads this before every command.",
            template = """
                # Profile

                > Anything here is given to the assistant with every command. Keep it short —
                > a long profile crowds out the command itself.

                - Name: Admin
                - Pronouns:
                - Timezone:
                - Wake time:
                - Work hours:
            """.trimIndent(),
        )

        val MEMORIES = MachineFile(
            id = "memories",
            fileName = "memories.md",
            title = "Memories",
            purpose = "Standing facts worth remembering — people, preferences, places.",
            template = """
                # Memories

                > Durable facts, one per line. "Osman is my brother" is useful here.
                > "I need milk" is a task, not a memory.

                -
            """.trimIndent(),
        )

        val TASKS = MachineFile(
            id = "tasks",
            fileName = "tasks.md",
            title = "Tasks",
            purpose = "What you have asked to be reminded of. The assistant writes here too.",
            template = """
                # Tasks

                > The assistant appends to this file when you ask it to remember something.
                > Tick a box to mark it done; it is an ordinary Markdown checklist.
            """.trimIndent(),
        )

        val NOTES = MachineFile(
            id = "notes",
            fileName = "notes.md",
            title = "Notes",
            purpose = "Anything else the assistant should know.",
            template = """
                # Notes

                > Free-form. Household details, recurring instructions, anything that does not
                > fit the other files.
            """.trimIndent(),
        )

        val ALL = listOf(PROFILE, MEMORIES, TASKS, NOTES)

        fun byId(id: String): MachineFile? = ALL.firstOrNull { it.id == id }
    }
}
