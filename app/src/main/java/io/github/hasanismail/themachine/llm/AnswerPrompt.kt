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
        // "If you do not know, say so" was in here, and the model took the invitation:
        // it answered "I don't know who painted the Mona Lisa." The permission to refuse
        // has to be narrower than the instruction to answer, or a 4B will take the
        // cheaper option. It is also told it has no internet, because otherwise it
        // declines current-affairs questions by explaining that it cannot browse.
        appendLine(
            "You are $adminName's assistant on their phone. Answer from your own knowledge, " +
                "in ONE short sentence, plainly and directly. Do not add a second sentence. " +
                "You are offline, so do not mention searching or browsing. Say you are not " +
                "sure only when you really are not.",
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

    /**
     * One sentence. Asked for two the 4B filled the second with whatever came to hand —
     * "a leap year has an extra month of February", and an unprompted opinion about the
     * user's brother — so the room for it is gone. It is also the answer being read
     * aloud, where a second sentence is a second thing to sit through.
     */
    const val MAX_TOKENS = 44

    private const val CONTEXT_BUDGET = 800
    private val NOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE HH:mm, d MMMM yyyy")
}
