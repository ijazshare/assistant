/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.assistant

import io.github.ijazshare.assistant.history.Resolution
import io.github.ijazshare.assistant.tools.Contact
import io.github.ijazshare.assistant.tools.ToolCall

internal data class PendingChoice(
    val transcript: String,
    val call: ToolCall,
    val sttMillis: Long,
    val llmMillis: Long,
    val resolution: Resolution,
    /** The unknown name that was heard, to save to the chosen contact's notes: the owner was told it would be. */
    val remember: String? = null,
)

/**
 * A text or call held for a name no contact answers to: "Did you mean Bilal?" or "Who is Be lol?" was just
 * asked about [heard], and the owner's answer within [PendingSend.CONFIRM_MILLIS] says who. The question
 * says the name will be remembered, so the answer is also the yes to saving [heard] in that contact's notes.
 */
internal data class PendingName(
    val heard: String,
    val held: PendingChoice,
    val suggestion: Contact?,
    val askedAtMillis: Long,
) {
    companion object {
        @Volatile
        private var waiting: PendingName? = null

        /** Not if the overlay was dismissed since the command began, as for a send. */
        @Synchronized
        fun holdUnlessDismissed(name: PendingName, dismissalsBefore: Int): Boolean {
            if (PendingSend.dismissals() != dismissalsBefore) return false
            waiting = name
            return true
        }

        /** Used once, expired or not, so the transcript after it is a command again. */
        fun take(now: Long): PendingName? {
            val name = waiting
            waiting = null
            return name?.takeIf { now - it.askedAtMillis <= PendingSend.CONFIRM_MILLIS }
        }

        fun dismiss() {
            waiting = null
        }

        // What can come before the name in an answer: "It's Bilal", "No, I meant Bilal Khan".
        private val LEAD_IN = setOf(
            "no", "nope", "i", "meant", "mean", "said", "it's", "its", "it", "is", "was", "that's", "thats", "um",
            "uh", "oh", "sorry", "the", "contact", "to", "my",
        )

        /** The name in an answer, without what came before it. */
        fun nameIn(answer: String): String =
            answer.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
                .dropWhile { it in LEAD_IN }.joinToString(" ")
    }
}
