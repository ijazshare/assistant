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

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The prompt a question is answered with, once the small model has decided it is one.
 *
 * Free text, no grammar: the point of escalating is a reply in words, and the larger
 * model is good at those. Ordered like the tool prompt — everything that does not change
 * first, the clock and the question last — so its cached prefix survives between
 * questions.
 */
object AnswerPrompt {

    private const val TURN_START = "<start_of_turn>"
    private const val TURN_END = "<end_of_turn>"

    fun build(
        question: String,
        adminName: String,
        userContext: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): String = buildString {
        append(TURN_START).appendLine("user")
        appendLine(
            "You are $adminName's assistant on their phone. Answer the question in one or two " +
                "short sentences, plainly and directly. If you do not know, say so in one sentence.",
        )
        if (userContext.isNotBlank()) {
            appendLine()
            appendLine("About $adminName:")
            appendLine(userContext.take(CONTEXT_BUDGET))
        }
        appendLine()
        appendLine("Now: ${now.format(NOW_FORMAT)}")
        appendLine("Question: $question")
        appendLine(TURN_END)
        append(TURN_START).appendLine("model")
    }

    /** Enough for two sentences; a question does not deserve a paragraph read aloud. */
    const val MAX_TOKENS = 80

    private const val CONTEXT_BUDGET = 800
    private val NOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE HH:mm, d MMMM yyyy")
}
