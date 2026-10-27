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

        /**
         * The send waiting for its words, for [WINDOW_MILLIS].
         *
         * Held beside the session rather than in it: the window is hidden and the session can be
         * rebuilt between the question and the answer. Only the expiry, the next transcript or a
         * dismissal ends it (see [dismiss]).
         */
        @Volatile
        private var held: PendingSend? = null

        fun hold(send: PendingSend) {
            held = send
        }

        /** Used once, expired or not, so the transcript after it is a command again. */
        fun take(now: Long): PendingSend? {
            val waiting = held
            held = null
            return waiting?.takeUnless { it.expired(now) }
        }

        /**
         * The owner walked away. A text cannot be undone, so words dictated at a later summon must
         * never reach a contact they were not said to: "What time is it?" is not a message.
         */
        fun dismiss() {
            held = null
        }

        // What a change of mind is made of. A cancel needs one of the cues and nothing else: "Cancel
        // that", "No, cancel", "Don't send anything", while "cancel my appointment" has words of its own.
        private val CANCEL_CUES = setOf(
            "cancel", "never", "mind", "nevermind", "forget", "stop", "no", "nope", "don't", "dont", "not",
        )
        private val CANCEL_FILLER = setOf(
            "that", "it", "this", "send", "sending", "anything", "please", "thanks", "thank", "you", "ok", "okay",
            "just", "do", "the", "message", "text", "actually", "oh", "sorry", "about",
        )

        // The first words of a fresh command, as opposed to the words of the message.
        private val NEW_COMMAND_VERBS = setOf(
            "send", "text", "message", "sms", "email", "whatsapp", "signal", "telegram", "dm", "call", "ring",
        )
        private val LEAD_INS = setOf("no", "nope", "actually", "instead", "oh", "wait", "sorry", "ok", "okay", "hey")

        // "Call me when you land" is dictation; "call Bob" is not.
        private val ME_WORDS = setOf("me", "you", "us", "back")

        private val NOT_LETTERS = Regex("[^a-z' ]")

        private fun wordsOf(transcript: String): List<String> =
            transcript.lowercase().replace('’', '\'').replace(NOT_LETTERS, " ").split(' ').filter { it.isNotEmpty() }

        /** Whether the owner changed their mind instead of dictating: "Never mind." is not a message. */
        fun isCancel(transcript: String): Boolean {
            val words = wordsOf(transcript)
            return words.any { it in CANCEL_CUES } && words.all { it in CANCEL_CUES || it in CANCEL_FILLER }
        }

        /**
         * Whether the owner, asked what to say, gave another order instead: "No, text Bob". It
         * would be sent to the first contact word for word, so it is a command, not an answer.
         */
        fun isNewCommand(transcript: String): Boolean {
            val words = wordsOf(transcript).dropWhile { it in LEAD_INS }
            val verb = words.firstOrNull() ?: return false
            return verb in NEW_COMMAND_VERBS && words.getOrNull(1) !in ME_WORDS
        }
    }
}
