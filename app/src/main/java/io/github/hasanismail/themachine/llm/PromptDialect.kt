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
import java.time.LocalDateTime

/**
 * How to talk to one family of model.
 *
 * This exists because a fine-tuned model is not a general model with a smaller budget —
 * it has been trained on one exact surface form, and handing it a different one makes it
 * ignore the request entirely rather than degrade gracefully. FunctionGemma answered
 * every question with the first example in a JSON-style prompt, because that prompt was
 * not the shape it was taught.
 *
 * A dialect owns all three sides of that contract together — the prompt, the grammar the
 * output is constrained to, and the parser that reads it back — so they cannot drift.
 */
interface PromptDialect {

    val id: String

    fun buildPrompt(
        transcript: String,
        tools: List<Tool>,
        adminName: String,
        userContext: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): String

    /** GBNF constraining the model to a well-formed call. */
    fun grammar(tools: List<Tool>): String

    fun parse(raw: String): ToolCall?

    companion object {
        /**
         * Chooses by model file name. Crude on purpose: the alternative is reading the
         * GGUF's chat template at runtime and inferring a dialect from it, which is far
         * more machinery for a decision that changes only when a model is added.
         */
        fun forModel(fileName: String): PromptDialect = when {
            fileName.contains("functiongemma", ignoreCase = true) -> FunctionGemmaDialect
            else -> JsonToolDialect
        }
    }
}
