/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tools

/** The shape of one argument a tool accepts. */
data class ToolParam(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = false,
    /** For [ParamType.ENUM]: the only values the grammar will permit. */
    val values: List<String> = emptyList(),
    /**
     * Bounds for an integer argument, enforced by the grammar rather than checked after.
     *
     * The model cannot then name an hour that is not on a clock: asked for "half past
     * six in the evening" it had been answering 16, converting to a 24-hour value on its
     * own and getting it wrong. With the range fixed at 1..12 that answer is not
     * expressible, and the conversion stays where it can be tested.
     */
    val range: IntRange? = null,
)

enum class ParamType { STRING, INTEGER, BOOLEAN, ENUM }

/**
 * Something the assistant can do.
 *
 * Tools are declared rather than inferred, and the declaration is what generates
 * the grammar the model is constrained by — so the model physically cannot call a
 * tool that does not exist or pass an argument that is not declared here. That is
 * the difference between a 1B model being usable for this and being a liability.
 */
data class Tool(
    val name: String,
    /** Written for the model, not for a docs page: short, concrete, and unambiguous. */
    val description: String,
    val params: List<ToolParam> = emptyList(),
    /** Which capability this needs, so the assistant can say why it cannot comply. */
    val requires: ToolCapability = ToolCapability.NONE,
)

/** What a tool needs from the system before it can run. */
enum class ToolCapability {
    NONE,
    ACCESSIBILITY,
    NOTIFICATION_ACCESS,
    SMS,
    CONTACTS,
    EXACT_ALARM,
}

/** A tool call the model asked for. */
data class ToolCall(val tool: String, val arguments: Map<String, String>) {
    fun string(name: String): String? = arguments[name]?.takeIf { it.isNotBlank() }
    fun int(name: String): Int? = arguments[name]?.toIntOrNull()
    fun bool(name: String): Boolean = arguments[name]?.equals("true", ignoreCase = true) == true
}

/** What happened when a tool ran. */
data class ToolResult(
    /** One sentence, phrased to be spoken aloud. */
    val spoken: String,
    val success: Boolean = true,
    /** Extra detail for the overlay; not spoken. */
    val detail: String? = null,
) {
    companion object {
        fun ok(spoken: String, detail: String? = null) = ToolResult(spoken, true, detail)
        fun failed(spoken: String, detail: String? = null) = ToolResult(spoken, false, detail)
    }
}
