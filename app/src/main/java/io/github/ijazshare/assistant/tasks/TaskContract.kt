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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

// The Tasks contract (spec 4.4 and 4.5), v1: what is sent and what comes back. The words are Tasks' own.

@Serializable
data class TaskOption(val id: String, val label: String)

@Serializable
data class TaskConversation(
    val id: String,
    val expects: String = "choice",
    @SerialName("expires_epoch_ms") val expiresEpochMs: Long = 0,
    val options: List<TaskOption> = emptyList(),
)

@Serializable
data class TaskUndo(val token: String, @SerialName("expires_epoch_ms") val expiresEpochMs: Long)

@Serializable
data class TaskDisplay(val title: String = "", val lines: List<String> = emptyList())

@Serializable
data class TaskError(val code: String, val detail: String = "")

@Serializable
data class TaskResponse(
    val status: String,
    val speech: String = "",
    val display: TaskDisplay? = null,
    val effects: List<JsonObject> = emptyList(),
    val conversation: TaskConversation? = null,
    val undo: TaskUndo? = null,
    val error: TaskError? = null,
    /** The `status` request's answer (the spec's second "status" key, renamed so it can sit beside the first). */
    val health: JsonObject? = null,
) {
    /** True when a migrate chunk reports that every chunk of the file has now arrived. */
    val doneAll get() = effects.any { (it["done_all"] as? JsonPrimitive)?.content == "true" }
}

/** One request. [type] is utterance, follow_up, cancel, undo, migrate, complete_by_legacy_id or status. */
data class TaskRequest(
    val type: String,
    val utterance: String? = null,
    val conversationId: String? = null,
    val optionId: String? = null,
    val undoToken: String? = null,
    val legacyId: String? = null,
    val lines: List<String>? = null,
    val chunk: Triple<Int, Int, String>? = null,
    val source: String = "voice",
    val routedBy: String = "keyword",
    val round: Int = 0,
    val speechEndMillis: Long = System.currentTimeMillis(),
    val requestId: String = UUID.randomUUID().toString(),
)

object TaskContract {
    const val VERSION = 1

    val json = Json { ignoreUnknownKeys = true }

    fun encode(r: TaskRequest, nowMillis: Long = System.currentTimeMillis(), zone: String, version: String): String =
        buildJsonObject {
            put("v", VERSION)
            put("request_id", r.requestId)
            put("type", r.type)
            r.utterance?.let { put("utterance", it) }
            r.conversationId?.let { put("conversation_id", it) }
            r.optionId?.let { put("option_id", it) }
            r.undoToken?.let { put("undo_token", it) }
            r.legacyId?.let { put("legacy_id", it) }
            r.lines?.let { put("lines", JsonArray(it.map(::JsonPrimitive))) }
            r.chunk?.let { (i, n, sha) ->
                put(
                    "chunk",
                    buildJsonObject {
                        put("index", i)
                        put("count", n)
                        put("file_sha256", sha)
                    },
                )
            }
            put(
                "context",
                buildJsonObject {
                    put("now_epoch_ms", nowMillis)
                    put("speech_end_epoch_ms", minOf(r.speechEndMillis, nowMillis))
                    put("zone", zone)
                    put("locale", java.util.Locale.getDefault().toLanguageTag())
                    put("hour_cycle", "h12")
                    put("source", r.source)
                    put("round", r.round)
                    put("routed_by", r.routedBy)
                },
            )
            put(
                "client",
                buildJsonObject {
                    put("package", "io.github.ijazshare.assistant")
                    put("version", version)
                },
            )
        }.toString()

    fun decode(text: String): TaskResponse? = runCatching { json.decodeFromString<TaskResponse>(text) }.getOrNull()
}
