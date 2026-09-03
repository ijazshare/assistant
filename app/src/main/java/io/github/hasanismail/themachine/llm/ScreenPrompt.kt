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

/**
 * The prompt for a question about what is on the screen.
 *
 * Deliberately not [AnswerPrompt]. That one instructs the model to answer from its own
 * knowledge, which is the opposite of what is wanted here — asked to summarise a chat it
 * would happily summarise a chat it invented. This one confines the model to the text it
 * is given and tells it to say so when that text does not contain the answer.
 *
 * No user-context block: it is irrelevant to reading a screen, and every token of it is
 * prefill the user waits through. Ordered like the others — fixed instruction first, the
 * volatile screen text and question last — so the cached prefix survives.
 */
object ScreenPrompt {

    private const val TURN_START = "<start_of_turn>"
    private const val TURN_END = "<end_of_turn>"

    fun build(screen: String, question: String, adminName: String, clipped: Boolean = false): String =
        buildString {
            append(TURN_START).appendLine("user")
            appendLine(
                "The text below was captured from $adminName's phone screen. It may be out of " +
                    "order, clipped, or misread. Use only that text: if it does not answer the " +
                    "question, say the screen does not show it. Reply in at most two short " +
                    "sentences, to be spoken aloud.",
            )
            appendLine()
            if (clipped) appendLine("(The screen was longer than this; the newest part is shown.)")
            appendLine("Screen:")
            appendLine(screen)
            appendLine()
            appendLine("Question: $question")
            appendLine(TURN_END)
            append(TURN_START).appendLine("model")
        }

    /**
     * Two short sentences. Longer than [AnswerPrompt.MAX_TOKENS] because a summary of a
     * screen genuinely needs more room than a fact does, and short enough that it is
     * still something a person will sit through being read to them.
     */
    const val MAX_TOKENS = 72

    /**
     * How much screen text is sent.
     *
     * The context is 2048 tokens and the model must have room to answer. This is roughly
     * 700 tokens of screen, which leaves the instruction, the question and the reply
     * comfortable. Going over does not degrade gracefully: the native layer returns an
     * empty string and the user is told the larger model is unavailable, for a model
     * they have installed.
     */
    const val SCREEN_BUDGET = 2_400
}
