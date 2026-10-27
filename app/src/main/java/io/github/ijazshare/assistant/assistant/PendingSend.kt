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

import java.util.concurrent.atomic.AtomicInteger

/**
 * A text the assistant has asked for the words of.
 *
 * "Text Sam" gets "What should I say to Sam?", and the owner's next dictation is the answer.
 * Without this the question was a dead end: the session forgot everything, and the message
 * went to the router as a fresh question. The recipient is held as the number it resolved to
 * plus the name to say back, so the reply goes to the same person even if the contacts
 * change in between.
 *
 * A reply that opens like an order ("Text Bob please") is not sent but read back: [body] is then
 * that reply, held for a yes.
 */
data class PendingSend(val number: String, val display: String, val askedAtMillis: Long, val body: String? = null) {

    /** Whether the answer is too late to be an answer: a transcript a minute on is a new command. */
    fun expired(now: Long): Boolean = now - askedAtMillis > if (body == null) WINDOW_MILLIS else CONFIRM_MILLIS

    companion object {
        const val WINDOW_MILLIS = 60_000L

        /** A read-back is a quick question, and a yes a minute later is not an answer to it. */
        const val CONFIRM_MILLIS = 30_000L

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

        private val dismissals = AtomicInteger()

        /** How many times the owner has walked away: what a command still running must not have outlived. */
        fun dismissals(): Int = dismissals.get()

        /**
         * Holds [send] unless the overlay was dismissed since [dismissalsBefore] was read, when the
         * command it came from began. A dismissal during transcription or inference is as final as one
         * after, and the words said at the next summon must not go to this contact.
         */
        @Synchronized
        fun holdUnlessDismissed(send: PendingSend, dismissalsBefore: Int): Boolean {
            if (dismissals.get() != dismissalsBefore) return false
            held = send
            return true
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
        @Synchronized
        fun dismiss() {
            dismissals.incrementAndGet()
            held = null
        }

        // What a change of mind is made of. A cancel needs one of the cues and nothing else: "Cancel
        // that", "No, cancel", "Don't send anything", while "cancel my appointment" has words of its own.
        private val CANCEL_CUES = setOf(
            "cancel", "never", "mind", "nevermind", "forget", "stop", "no", "nope", "don't", "dont", "not",
            "wait", "hold", "changed",
        )
        private val CANCEL_FILLER = setOf(
            "that", "it", "this", "send", "sending", "anything", "please", "thanks", "thank", "you", "ok", "okay",
            "just", "do", "the", "message", "text", "actually", "oh", "sorry", "about", "on", "him", "her", "them",
            "to", "i", "i'll", "my", "call",
        )

        // A reply that opens like this is the end of the request, whatever follows: "Never mind, see you
        // at six" is not for Sam. "Cancel my appointment" is a message; "cancel the message" is not.
        private val STRONG_CANCEL = Regex(
            "^(?:(?:wait|oh|ok|okay|sorry|actually|um|uh) )*(?:never ?mind|forget (?:it|that)|" +
                "(?:don't|dont|do not) (?:send|text|message)|(?:i )?changed my mind|" +
                "cancel(?: (?:that|it|this|the (?:message|text)|sending|send|please)|$))",
        )

        // What a correction of the name is made of: "No, Bob", "No, I said Bob", "Not Sam, Bob".
        private val CORRECTION_OPENERS = setOf("no", "nope", "not", "wrong")
        private val CORRECTION_FILLER = setOf(
            "i", "said", "meant", "mean", "it's", "its", "was", "is", "to", "that", "the", "person", "contact",
            "text", "send", "message", "tell", "ask", "please", "sorry", "i'm", "it", "not", "no",
        )

        // A reply that starts with one of these, after the politeness, may be an order and not dictation.
        private val ORDER_OPENING = Regex(
            "^(?:(?:please|um|uh|actually|so|ok|okay|can you|could you) )*" +
                "(?:text|send|message|call|ring|phone|email|tell|dm|whatsapp)(?: |$)",
        )
        private val YES = setOf(
            "yes", "yeah", "yep", "yup", "sure", "send", "send it", "go ahead", "do it", "correct", "ok", "okay",
        )

        private const val MAX_NAME_WORDS = 2

        private val NOT_LETTERS = Regex("[^a-z' ]")
        private val NOT_LETTERS_ANY_CASE = Regex("[^A-Za-z' ]")

        private fun wordsOf(transcript: String): List<String> =
            transcript.lowercase().replace('’', '\'').replace(NOT_LETTERS, " ").split(' ').filter { it.isNotEmpty() }

        /**
         * Whether the owner changed their mind instead of dictating: "Never mind." is not a message.
         * [recipient] is the name the question was asked with, which "Cancel the message to Sam" says.
         */
        fun isCancel(transcript: String, recipient: String = ""): Boolean {
            val words = wordsOf(transcript)
            if (STRONG_CANCEL.containsMatchIn(words.joinToString(" "))) return true
            val names = wordsOf(recipient).toSet()
            return words.any { it in CANCEL_CUES } &&
                words.all { it in CANCEL_CUES || it in CANCEL_FILLER || it in names }
        }

        /**
         * Whether the owner, asked what to say for [recipient], corrected the name instead of dictating:
         * "No, Bob", "No, I said Bob", "Not Sam, Bob". Whisper mishears names, so this is the obvious
         * reply, and it would be sent to the first contact word for word. Dropping it is the safe side:
         * a text cannot be unsent, and the order can be given again.
         *
         * Only a reply that opens with a no or not and then says nothing but a capitalised name or two
         * (or the first contact's own name): "No problem, see you at six" and "No, I'm not coming" are
         * messages.
         */
        fun isCorrection(transcript: String, recipient: String = ""): Boolean {
            val said = transcript.replace('’', '\'').replace(NOT_LETTERS_ANY_CASE, " ").trim().split(' ')
                .filter { it.isNotEmpty() }
            if (said.firstOrNull()?.lowercase() !in CORRECTION_OPENERS) return false
            val names = wordsOf(recipient).toSet()
            val rest = said.drop(1).filter { it.lowercase() !in CORRECTION_FILLER }
            val named = rest.filter { it.lowercase() !in names }
            val mentionsFirst = rest.size > named.size
            return named.size <= MAX_NAME_WORDS && named.all { it.first().isUpperCase() } &&
                (named.isNotEmpty() || mentionsFirst)
        }

        /**
         * Whether the reply opens like an order to text or call someone, which could be one that landed
         * in the answer slot ("Text Bob please") or the message ("Text me when you land").
         */
        fun startsWithVerb(
            transcript: String,
        ): Boolean = ORDER_OPENING.containsMatchIn(wordsOf(transcript).joinToString(" "))

        /** Whether the answer to a read-back is a yes: anything else is a no. */
        fun isYes(transcript: String): Boolean = wordsOf(transcript).joinToString(" ") in YES
    }
}
