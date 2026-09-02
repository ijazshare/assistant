/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.llm

import io.github.hasanismail.themachine.tools.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the model's JSON into a [ToolCall].
 *
 * The grammar already guarantees well-formed JSON with a known tool name, so this is
 * not defensive parsing — it is a straight read. It still returns null rather than
 * throwing, because the grammar only applies when one was supplied, and a caller that
 * forgets should get a clean "I did not understand" rather than a crash.
 *
 * Values are flattened to strings deliberately: the grammar has already constrained
 * each one to the right shape, and a single string map keeps [ToolCall] free of a
 * type hierarchy that would earn nothing.
 */
object ToolCallParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): ToolCall? {
        // A model that stops mid-object leaves the braces unbalanced; find the object.
        // An empty string needs no separate check: it has no brace either.
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        if (start < 0) return null

        // A call stopped the moment its tool name closed has no closing braces yet. It is
        // completed here rather than refused: the grammar guarantees what came before was
        // well-formed, and the caller asked for exactly this.
        val end = trimmed.lastIndexOf('}')
        val objectText = if (end > start) trimmed.substring(start, end + 1) else trimmed.substring(start) + "}"

        val element = runCatching {
            json.parseToJsonElement(objectText).jsonObject
        }.getOrNull() ?: return null

        val tool = element["tool"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val arguments = (element["arguments"] as? JsonObject)
            ?.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNullSafe()?.let { key to it }
            }
            ?.toMap()
            .orEmpty()

        return ToolCall(tool, arguments)
    }

    /** JSON null reads back as the literal string "null", which is never a real value. */
    private fun JsonPrimitive.contentOrNullSafe(): String? =
        content.takeIf { it.isNotBlank() && !(isString.not() && it == "null") }
}
