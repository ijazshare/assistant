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
import java.util.UUID

/** One of the Markdown files that make up what the assistant knows about you. */
data class MachineFile(
    val id: String,
    val fileName: String,
    val title: String,
    val purpose: String,
    /** What a new install starts with — an example, not an empty page. */
    val template: String,
)

/** One line of the tasks checklist, as the code sees it. */
data class TaskLine(
    /** Null on a line the user wrote by hand; only the assistant's lines carry one. */
    val id: String?,
    val title: String,
    val due: LocalDateTime?,
    val done: Boolean,
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
class MachineFiles(private val root: File) {

    constructor(context: Context) : this(File(context.getExternalFilesDir(null), "context"))

    fun file(spec: MachineFile): File = File(root.apply { mkdirs() }, spec.fileName)

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
     * Appends a task and returns its id.
     *
     * Written by the assistant, but in the same file and the same format the user edits
     * by hand — there is no separate machine-only store. The id and an ISO due time ride
     * in the trailing comment, which the reader never has to look at and the model is
     * never shown, so that an alarm can find its line again after a reboot.
     */
    fun appendTask(task: String, due: LocalDateTime?): String {
        val target = file(TASKS)
        if (!target.exists()) target.writeText(TASKS.template)
        val id = UUID.randomUUID().toString().take(ID_LENGTH)
        val stamp = LocalDateTime.now().format(STAMP)
        val dueText = due?.let { " — due ${it.format(DUE)}" } ?: ""
        val dueField = due?.let { " due:${it.format(ISO)}" } ?: ""
        target.appendText("\n- [ ] $task$dueText  <!-- id:$id$dueField added $stamp -->")
        return id
    }

    /** Every checklist line in the tasks file, in file order. */
    fun readTasks(): List<TaskLine> {
        val target = file(TASKS)
        if (!target.isFile) return emptyList()
        return target.readLines().mapNotNull { parseTask(it) }
    }

    /** Ticks a task's box. False if no line carries that id. */
    fun completeTask(id: String): Boolean =
        rewriteTask(id) { line -> line.replaceFirst("- [ ]", "- [x]") }

    /** Moves a task's due time, in both the words the user reads and the field the code does. */
    fun rescheduleTask(id: String, due: LocalDateTime): Boolean = rewriteTask(id) { line ->
        val match = TASK_LINE.matchEntire(line.trimEnd()) ?: return@rewriteTask line
        val title = match.groupValues[TITLE_GROUP].substringBefore(DUE_MARKER).trim()
        val fields = match.groupValues[FIELDS_GROUP]
            .let { DUE_FIELD.replace(it, "due:${due.format(ISO)}") }
            .let { if (DUE_FIELD.containsMatchIn(it)) it else "$it due:${due.format(ISO)}" }
            .trim()
        "- [${match.groupValues[BOX_GROUP]}] $title$DUE_MARKER ${due.format(DUE)}  <!-- $fields -->"
    }

    private fun parseTask(line: String): TaskLine? {
        val match = TASK_LINE.matchEntire(line.trimEnd()) ?: return null
        val fields = match.groupValues[FIELDS_GROUP]
        return TaskLine(
            id = ID_FIELD.find(fields)?.groupValues?.get(1),
            title = match.groupValues[TITLE_GROUP].substringBefore(DUE_MARKER).trim(),
            due = DUE_FIELD.find(fields)?.groupValues?.get(1)
                ?.let { runCatching { LocalDateTime.parse(it, ISO) }.getOrNull() },
            done = match.groupValues[BOX_GROUP].equals("x", ignoreCase = true),
        )
    }

    private fun rewriteTask(id: String, transform: (String) -> String): Boolean {
        val target = file(TASKS)
        if (!target.isFile) return false
        var changed = false
        val lines = target.readLines().map { line ->
            if (!changed && ID_FIELD.find(line)?.groupValues?.get(1) == id) {
                changed = true
                transform(line)
            } else {
                line
            }
        }
        if (changed) target.writeText(lines.joinToString("\n"))
        return changed
    }

    /** Everything the model should be told, concatenated in a stable order. */
    fun contextForPrompt(): String = ALL
        .filter { isCustomised(it) || it.id == TASKS.id }
        .joinToString("\n\n") { spec ->
            "## ${spec.title}\n" + read(spec).lineSequence()
                // Strip the guidance comments; they are for the user, not the model.
                .filterNot { it.trimStart().startsWith("<!--") || it.trimStart().startsWith(">") }
                // And the bookkeeping on task lines, which is for the code.
                .map { it.replace(INLINE_COMMENT, "").trimEnd() }
                .joinToString("\n")
                .trim()
        }
        .trim()

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val DUE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        private const val DUE_MARKER = " — due"
        private const val ID_LENGTH = 8

        // "- [ ] title — due Tue 2 Sep, 7:00 PM  <!-- id:ab12cd34 due:2026-09-02T19:00 added ... -->"
        private val TASK_LINE = Regex("""^- \[([ xX])\] (.*?)\s*(?:<!--(.*?)-->)?\s*$""")
        private const val BOX_GROUP = 1
        private const val TITLE_GROUP = 2
        private const val FIELDS_GROUP = 3
        private val ID_FIELD = Regex("""\bid:(\S+)""")
        private val DUE_FIELD = Regex("""\bdue:(\S+)""")
        private val INLINE_COMMENT = Regex("""\s*<!--.*?-->""")

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
