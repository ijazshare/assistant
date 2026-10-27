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

/**
 * A text the assistant has asked for the words of.
 *
 * "Text Sam" gets "What should I say to Sam?", and the owner's next dictation is the answer.
 * Without this the question was a dead end: the session forgot everything, and the message
 * went to the router as a fresh question. The recipient is held as the number it resolved to
 * plus the name to say back, so the reply goes to the same person even if the contacts
 * change in between.
 */
data class PendingSend(val number: String, val display: String, val askedAtMillis: Long) {

    /** Whether the answer is too late to be an answer: a transcript a minute on is a new command. */
    fun expired(now: Long): Boolean = now - askedAtMillis > WINDOW_MILLIS

    companion object {
        const val WINDOW_MILLIS = 60_000L

        private val CANCEL_PHRASES = setOf(
            "cancel",
            "never mind",
            "nevermind",
            "stop",
            "forget it",
            "don't send it",
        )

        private val NOT_LETTERS = Regex("[^a-z' ]")

        /** Whether the owner changed their mind instead of dictating: "Never mind." is not a message. */
        fun isCancel(transcript: String): Boolean {
            val said = transcript.lowercase().replace('’', '\'').replace(NOT_LETTERS, " ")
                .trim().replace(Regex("\\s+"), " ")
            return said in CANCEL_PHRASES
        }
    }
}
