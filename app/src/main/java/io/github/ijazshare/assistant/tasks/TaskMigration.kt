/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.tasks

import android.content.Context
import io.github.ijazshare.assistant.context.AssistantFiles
import io.github.ijazshare.assistant.settings.AssistantSettings
import io.github.ijazshare.assistant.tools.ReminderStore
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Moves tasks.md and its armed reminders into Tasks (spec 4.13). The raw lines go over in chunks and Tasks reads
 * them; only when it says every chunk is in does this cancel its own alarms, rename the file and remember it.
 * Any failure leaves the file and the alarms alone, and the whole thing runs again next time.
 */
class TaskMigration(private val context: Context, private val client: TaskAppClient = TaskAppClient.of(context)) {
    private val files = AssistantFiles(context)
    private val settings = AssistantSettings(context)

    /** One line for Settings: whether Tasks answers, and whether the old list has moved. */
    suspend fun status(): String {
        val answer = client.send(TaskRequest("status"))
        client.sessionEnded()
        val moved = if (settings.tasksMigratedNow()) "old list moved" else "old list not moved yet"
        return if (answer.status == "done") "Linked, $moved." else answer.speech
    }

    /** One sentence for the Settings screen: what happened. */
    suspend fun run(): String = try {
        move()
    } finally {
        client.sessionEnded()
    }

    @Suppress("ReturnCount") // each early return leaves the file and the alarms alone
    private suspend fun move(): String {
        val file = files.file(AssistantFiles.TASKS)
        if (!file.isFile) return done("There was no old list to move.")
        // Tasks must be there and answering before anything is read, let alone cancelled.
        if (client.send(TaskRequest("status")).status != "done") return "Tasks did not answer, so nothing moved."
        val bytes = file.readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val chunks = chunkLines(bytes.toString(Charsets.UTF_8).lines())
        var all = false
        chunks.forEachIndexed { i, lines ->
            val answer = client.send(TaskRequest("migrate", lines = lines, chunk = Triple(i, chunks.size, sha)))
            if (answer.status != "done") return "Tasks refused part of the list, so nothing moved."
            all = answer.doneAll
        }
        if (!all) return "Tasks did not confirm the whole list, so nothing moved."
        val store = ReminderStore(context)
        files.readTasks().forEach { it.id?.let(store::cancelAlarm) }
        val stamp = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val backup = File(file.parentFile, "tasks.migrated-$stamp.md")
        if (!file.renameTo(
                backup,
            )
        ) {
            return "The list was copied, but the old file would not rename. It will be tried again."
        }
        return done("Moved your old list into Tasks.")
    }

    private suspend fun done(message: String): String {
        settings.setTasksMigrated()
        return message
    }
}

/** At most 200 lines and 128 KiB a chunk; an empty file is one empty chunk. */
internal fun chunkLines(lines: List<String>): List<List<String>> {
    val out = mutableListOf<MutableList<String>>()
    var bytes = 0
    for (line in lines) {
        val size = line.toByteArray().size + 1
        if (out.isEmpty() || out.last().size >= MAX_CHUNK_LINES || bytes + size > MAX_CHUNK_BYTES) {
            out.add(mutableListOf())
            bytes = 0
        }
        out.last() += line
        bytes += size
    }
    return out.ifEmpty { listOf(emptyList()) }
}

private const val MAX_CHUNK_LINES = 200
private const val MAX_CHUNK_BYTES = 128 * 1024
