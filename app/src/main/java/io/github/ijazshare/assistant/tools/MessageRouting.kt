/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.tools

object MessageRouting {
    private const val ASK = "^(?:(?:please|can you|could you|would you|hey|okay|ok)[ ,]+)*"
    private const val NOUN = "(?:text|message|sms|txt|whatsapp|what['’]?s ?app)(?: message)?"
    private val TEXT_FIRST =
        Regex(
            "$ASK(?:text|message|msg|sms|txt|whatsapp|what['’]?s ?app)[ ,]+(?:to )?(?:(?:my|the) )?(\\p{L}+)",
            RegexOption.IGNORE_CASE,
        )
    private val SEND_TO =
        Regex("${ASK}send (?:an? )?(?:quick )?$NOUN to (?:(?:my|the) )?(\\p{L}+)", RegexOption.IGNORE_CASE)
    private val SEND_NAME =
        Regex("${ASK}send (?:(?:my|the) )?(\\p{L}+) an? (?:quick )?$NOUN\\b", RegexOption.IGNORE_CASE)

    // Words after "text" that make it a noun or a pronoun, not a person: "text on the screen", "text me".
    private val NOT_A_PERSON = setOf(
        "me", "myself", "us", "him", "her", "them", "it", "this", "that", "these", "those", "on", "from", "about",
        "in", "of", "is", "was", "says", "message", "messages", "to", "back", "and", "or", "the", "a", "an", "my",
        "when", "if", "at", "for",
    )

    /**
     * Who a sentence that opens as a request to text names ("Text Sam ...", "Message Sam ...", "Send a text to
     * Sam", "Send Sam a message", "WhatsApp Sam ..."), or null when it is not one. Read by code so the
     * model cannot send it to read_screen; the executor still earns the contact match, asks for the body and
     * confirms.
     */
    // ponytail: one word of name; the contact lookup matches whole words of a display name anyway.
    fun addressee(transcript: String): String? {
        val said = transcript.trim().trimEnd('.', '?', '!', ' ')
        val name = listOf(TEXT_FIRST, SEND_TO, SEND_NAME).firstNotNullOfOrNull { it.find(said)?.groupValues?.get(1) }
        return name?.takeIf { it.lowercase() !in NOT_A_PERSON }
    }
}
