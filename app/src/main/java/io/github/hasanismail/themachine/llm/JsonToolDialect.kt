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

import io.github.hasanismail.themachine.tools.Tool
import io.github.hasanismail.themachine.tools.ToolCall
import io.github.hasanismail.themachine.tools.ToolGrammar
import java.time.LocalDateTime

/**
 * JSON tool calls for general instruction-tuned models such as Gemma 3.
 *
 * These have no trained tool-call surface of their own, so a compact instruction plus
 * worked examples plus a JSON grammar is the most reliable shape — and the grammar
 * makes the output well-formed regardless of how well the model followed the prose.
 */
object JsonToolDialect : PromptDialect {

    override val id: String = "json"

    override fun buildPrompt(
        transcript: String,
        tools: List<Tool>,
        adminName: String,
        userContext: String,
        now: LocalDateTime,
    ): String = PromptBuilder.build(transcript, tools, adminName, userContext, now)

    override fun grammar(tools: List<Tool>): String = ToolGrammar.build(tools)

    // Plain escapes, not a raw string: as a raw string this held a literal backslash,
    // so it never matched anything the model wrote and the early stop never fired. The
    // 1B then wrote out a whole answer, blew the token cap, and the question came back
    // as "I could not work out what to do with that".
    override val answerMarker: String = "\"tool\":\"answer\""

    override fun parse(raw: String): ToolCall? = ToolCallParser.parse(raw)
}
