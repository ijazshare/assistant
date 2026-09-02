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

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** How a command reached the assistant. */
@Serializable
enum class QuerySource { VOICE, TYPED }

/** How it was resolved: instantly from the cache, by the model, or not at all. */
@Serializable
enum class Resolution { CACHE, MODEL, FAILED }

/** One command, from what was heard to what was done. */
@Serializable
data class QueryRecord(
    val atEpochMillis: Long,
    val transcript: String,
    val source: QuerySource,
    val resolution: Resolution,
    val tool: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    val spoken: String,
    val success: Boolean,
    val sttMillis: Long = 0,
    val llmMillis: Long = 0,
)

/**
 * Every command the assistant has handled.
 *
 * Stored as one JSON object per line rather than a database, for the same reason the
 * context files are Markdown: it is the user's own record, it should survive being read
 * by anything, and appending a line is the whole write. It stays on the device; nothing
 * here is sent anywhere, in keeping with the rest of the app.
 */
class QueryLog(private val file: File) {

    constructor(context: Context) : this(
        File(File(context.getExternalFilesDir(null), "history").apply { mkdirs() }, FILE_NAME),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Appends one record, trimming the file when it grows past its bound.
     *
     * Trimming rewrites the file, which is the one expensive step, so it happens only
     * every [TRIM_SLACK] records past [KEEP] rather than on every append.
     */
    @Synchronized
    fun append(record: QueryRecord) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(json.encodeToString(record) + "\n")
            val lines = file.readLines()
            if (lines.size > KEEP + TRIM_SLACK) {
                file.writeText(lines.takeLast(KEEP).joinToString("\n", postfix = "\n"))
            }
        }.onFailure { Log.w(TAG, "history: could not append", it) }
    }

    /** Newest first. A line that will not parse is skipped rather than poisoning the rest. */
    @Synchronized
    fun recent(limit: Int = KEEP): List<QueryRecord> {
        if (!file.isFile) return emptyList()
        return file.readLines().asReversed().asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.decodeFromString<QueryRecord>(line) }.getOrNull() }
            .take(limit)
            .toList()
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        private const val TAG = "TheMachine"
        const val FILE_NAME = "queries.jsonl"

        /** Records kept after a trim. */
        const val KEEP = 500

        /** Records allowed to accumulate past [KEEP] before a trim is worth its rewrite. */
        const val TRIM_SLACK = 100
    }
}
