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

/**
 * Guards against a fabricated message body.
 *
 * A message body is an unconstrained string, and asked to text someone with no words to
 * send, the model fills the slot anyway — "hello how are you" for a bare "text Dad". A
 * text is one of the few actions with no undo, so an invented body must never be sent.
 * The words of a real message are the words the user actually said; a body whose words
 * are mostly absent from the transcript was not dictated, it was invented.
 */
object MessageBody {

    /** Below this fraction of body words found in the transcript, the body was invented. */
    private const val TRACEABLE_FRACTION = 0.5f

    /** Shorter body words must appear whole in the transcript, not inside another word. */
    private const val MIN_INFIX = 4

    private val WORD = Regex("[^\\p{L}\\p{N}]+")
    private val FOR_ME = Regex("\\bfor me\\b")

    /**
     * Words every messaging command carries, whatever the message: never evidence of one.
     * The apps and the little words around them too, since the model echoes the request
     * itself as the body: "Send an email", "WhatsApp", "Message my brother".
     */
    private val COMMAND_WORDS = setOf(
        "text", "texts", "message", "messages", "send", "sms", "tell",
        "reply", "write", "say", "saying", "that", "to",
        "email", "emails", "whatsapp", "signal", "telegram", "dm", "msg", "txt", "ping",
        "an", "the", "my",
    )

    /** Apps a message could be meant for other than SMS, which is all this can send. */
    private val OTHER_APPS = setOf(
        "signal", "whatsapp", "telegram", "email", "emails", "mail", "gmail", "messenger", "facebook",
        "instagram", "discord", "slack", "viber",
    )

    // Whisper writes the app as two words ("what's app"), and "e-mail" splits in two.
    private val WHATS_APP = Regex("""\bwhat['’]?s ?app\b""")
    private val E_MAIL = Regex("""\be[- ]mail""")

    /** The verbs that open a request to send: what is said after them is the request, not a message. */
    private val REQUEST_VERBS = setOf(
        "send", "text", "message", "sms", "email", "mail", "whatsapp", "signal", "telegram", "dm", "msg", "txt",
        "ping", "tell", "write", "reply",
    )

    /**
     * What may follow the verb of a request, or come before it, without being a message: the
     * message and the person it is meant for, by name or pronoun, and the politeness around
     * them. One word that is none of these — "send me the *report*" — and it is dictation.
     */
    private val REQUEST_WORDS = setOf(
        "now", "please", "ok", "okay", "then", "and", "can", "could", "you", "just", "go", "ahead",
        "it", "that", "this", "a", "an", "the", "to", "him", "her", "them", "over", "off", "again",
        "message", "messages", "text", "texts", "sms", "email", "whatsapp", "signal", "telegram", "dm",
        "msg", "txt", "quick", "quickly", "real", "right", "hey", "for", "up", "on", "via", "using", "through",
        "mail", "gmail", "messenger", "facebook", "instagram", "discord", "slack", "viber",
    )

    /**
     * True if [body] is the request to send rather than words to send: "Now send that message
     * to Sam Lee", "send it to Sam", "text Sam", "send Sam a message".
     *
     * The model hands back the request as the body when none was dictated, and it passes
     * [isTraceable] because the user did say those words. A request is a command verb, then
     * nothing but the recipient and the little words of asking; dictation after "text Sam"
     * has words of its own, so "text Sam I'll be late" is untouched (its body, "I'll be
     * late", never starts with a verb), and so is "send me the report".
     */
    fun isRequest(body: String, recipient: String = ""): Boolean {
        val named = recipient.lowercase().split(WORD).toSet()
        // "For me" is politeness ("text Sam for me"); a "me" anywhere else is dictation: "text me when you land".
        val words = body.lowercase().replace(FOR_ME, " ").split(WORD).filter { it.isNotEmpty() && it !in named }
        val verb = words.indexOfFirst { it in REQUEST_VERBS }
        // Only politeness may stand in front of the verb: "Now send ...", "Can you text ...".
        if (verb < 0 || words.take(verb).any { it !in REQUEST_WORDS }) return false
        return words.drop(verb + 1).all { it in REQUEST_WORDS }
    }

    /**
     * True if the request names an app other than SMS: "Send a Signal message to Sam", "WhatsApp
     * Yusuf", "message Sam on Telegram". Sent as a text, the words would go somewhere the user did
     * not ask for. A word the user dictated ("the signal is bad here") is part of the message and
     * does not count, unless the body is only the request echoed back.
     */
    fun namesAnotherApp(transcript: String, body: String, recipient: String = ""): Boolean {
        val echoed = body.isBlank() || isRequest(spelledOut(body), recipient)
        val dictated = if (echoed) emptyList() else appsIn(body)
        // Counted, not looked up: "WhatsApp Yusuf the whatsapp group is dead" names it twice, once as the app.
        return appsIn(transcript).groupingBy {
            it
        }.eachCount().any { (app, said) -> said > dictated.count { it == app } }
    }

    /** True if [body] is the whole of [transcript], request and all, as the model sometimes copies it. */
    fun isEchoed(transcript: String, body: String): Boolean {
        val said = transcript.trim().trimEnd('.', '!', '?')
        return said.isNotEmpty() && body.trim().trimEnd('.', '!', '?').equals(said, ignoreCase = true)
    }

    private fun spelledOut(text: String) = text.lowercase().replace(WHATS_APP, "whatsapp").replace(E_MAIL, "email")

    private fun appsIn(text: String) = spelledOut(text).split(WORD).filter { it in OTHER_APPS }

    /**
     * True if [body] looks like something the user actually said in [transcript] when
     * texting [recipient].
     *
     * A body with no word of two letters or more (a smiley, "k") has nothing to fabricate
     * and is allowed through. Otherwise at least half its real words must appear in the
     * transcript — enough to pass an ordinary paraphrase ("I'm" heard as "I am") while
     * catching a body the model wrote out of nothing. A body that is nothing but the
     * recipient and the command was invented outright.
     */
    fun isTraceable(transcript: String, body: String, recipient: String = ""): Boolean {
        val all = body.lowercase().split(WORD).filter { it.length >= 2 }
        if (all.isEmpty()) return true
        // The recipient's name is in every "text <name>" transcript, so finding it proves
        // nothing: "Text Hasan" with an invented "Hello Hasan" was half "found" and sent.
        // Nor do the command's own words: the model also echoes "Text Hasan" as the body.
        // Both leave the transcript as well, or "hi" is found inside "Ibrahim".
        val named = recipient.lowercase().split(WORD).toSet() + COMMAND_WORDS
        val words = all.filterNot { it in named }
        if (words.isEmpty()) return false
        val heard = transcript.lowercase().split(WORD).filterNot { it in named }
        // A long word may be found inside a longer one ("late" in "later"); a short one only
        // whole, or an invented "ok" was found in "okay" and sent.
        val found = words.count { word ->
            if (word.length < MIN_INFIX) word in heard else heard.any { it.contains(word) }
        }
        return found.toFloat() / words.size >= TRACEABLE_FRACTION
    }
}
