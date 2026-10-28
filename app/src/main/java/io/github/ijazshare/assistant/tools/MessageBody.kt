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
 * Where a text's words come from: what the user said, never the model.
 *
 * The small router model fills a message body whatever was said, and a text is one of the few
 * actions with no undo: it invented "hello how are you" for a bare "text Dad", copied the request
 * ("Text Sam please") or the whole sentence. So the model's body is thrown away, and [fromWords]
 * takes the message out of the transcript, from the plain shape only.
 */
object MessageBody {

    private val WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")
    private val FOR_ME = Regex("\\bfor me\\b")

    /** Apps a message could be meant for other than SMS, which is all this can send. */
    private val OTHER_APPS = setOf(
        "signal", "whatsapp", "telegram", "email", "emails", "mail", "gmail", "messenger", "facebook",
        "instagram", "discord", "slack", "viber",
    )

    // Whisper writes the app as two words ("what's app"), and "e-mail" splits in two.
    private val WHATS_APP = Regex("""\bwhat['’]?s ?app\b""", RegexOption.IGNORE_CASE)
    private val E_MAIL = Regex("""\be[- ]mail""", RegexOption.IGNORE_CASE)

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
     * The model handed back the request as the body when none was dictated, and the user did say
     * those words, as the command and not as the message. A request is a command verb, then
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

    private fun spelledOut(text: String) = text.lowercase().replace(WHATS_APP, "whatsapp").replace(E_MAIL, "email")

    private fun appsIn(text: String) = spelledOut(text).split(WORD).filter { it in OTHER_APPS }

    /** Words of pure politeness: a reply made only of them ("Text Sam please") is no message. */
    private val POLITENESS = setOf(
        "please", "now", "thanks", "thank", "you", "ok", "okay", "quick", "quickly", "real", "right", "just",
        "for", "me",
    )

    /** What may come between the recipient and the words: "a text", "a quick message", "a text message". */
    private val MESSAGE_NOUNS = setOf("text", "message", "sms", "msg", "note")
    private val ARTICLES = setOf("a", "an")

    /** What may introduce the words themselves, longest first: "a text that says ...", "tell Sam to ...". */
    private val JOINERS = listOf(
        "to say that",
        "saying that",
        "that says",
        "which says",
        "to say",
        "that",
        "saying",
        "to",
    )
        .map { it.split(" ") }

    /**
     * Words that carry the request on past the name, so what follows is not yet the message: "back",
     * "again", "and tell him", "about dinner", "asking if", "a WhatsApp", "that says that". A message
     * that opens with one ("Back home now") is asked for, never guessed at.
     */
    private val CARRIES_ON = setOf(
        "a", "an", "and", "or", "then", "also", "too", "as", "back", "again", "about", "asking", "telling",
        "something", "if", "whether", "that", "to", "say", "says", "said", "saying", "for",
        "later", "tomorrow", "tonight", "real", "quick", "quickly",
    ) + (REQUEST_VERBS - "tell") + MESSAGE_NOUNS + OTHER_APPS

    /** The request again, about the one being texted; "Let me know ..." and "Tell Ali ..." are messages. */
    private val TELLING_THEM = setOf(
        "tell him", "tell her", "tell them", "let him", "let her", "let them", "ask him", "ask her", "ask them",
    )

    /** Words that put an app next, "on WhatsApp", "via his Signal": the request, not the message. */
    private val BY_WAY_OF = setOf("on", "over", "in", "with", "via", "using", "through", "by")

    /** "I", "I'm", "I'll": the capital letters that do not carry a name on. */
    private val I_FORM = Regex("i(['’]\\p{L}+)?")

    /** What may stand before the recipient: the request, and "my" as in "text my wife". */
    private val BEFORE_RECIPIENT = REQUEST_WORDS + REQUEST_VERBS + "my"

    private const val WITH_COUNTRY_CODE = 11

    /**
     * The message the user dictated for [recipient], or null when there is none, so the executor
     * asks what to say.
     *
     * Safe by construction: it acts only on the plain shape, a request, the recipient, an
     * optional "a text", an optional "that", "saying", "to say" or "that says", then the words.
     * "Text Sam I'm late", "Send Sam a text that says hi" and "Tell Sam to call me" give the words
     * after the name. Anything else gives null: "Let Sam know I'm late" and "Sam I'm late" are not
     * that shape, "Text Sam back", "Text Sam on WhatsApp ..." and "Text Sam and tell him ..." carry
     * the request on past the name, "Text Sam Lee ..." carries on a name the model cut short, and
     * "Text Sam please" has nothing after the name but politeness.
     */
    fun fromWords(transcript: String, recipient: String): String? {
        // One spelling of each app, and no lone punctuation ("-"), which would hide the first word from the checks.
        val tokens = transcript.replace(WHATS_APP, "WhatsApp").replace(E_MAIL, "email").trim().split(SPACES)
            .filter { token -> token.any(Char::isLetterOrDigit) }
        val said = tokens.map(::bare)
        val run = recipientRun(said, recipient) ?: return null
        // Before the recipient only the request may stand, and it needs a verb: "Can you text my ...".
        val before = said.subList(0, run.first)
        val asked = before.any { it in REQUEST_VERBS } && before.all { it in BEFORE_RECIPIENT }
        // A capital straight after the name carries the name on, unless it is "I" or a pause came
        // between ("Sam. Running late").
        val next = tokens.getOrNull(run.last + 1)
        val nameGoesOn = next != null && tokens[run.last].last().isLetterOrDigit() &&
            next.first(Char::isLetterOrDigit).isUpperCase() && !I_FORM.matches(bare(next))
        if (!asked || nameGoesOn) return null
        val from = messageStart(said, run.last + 1)
        val opening = said.drop(from)
        val carriesOn = opening.firstOrNull() in CARRIES_ON || opening.take(2).joinToString(" ") in TELLING_THEM ||
            (opening.firstOrNull() in BY_WAY_OF && opening.drop(1).take(2).any { it in OTHER_APPS })
        if (carriesOn || opening.all { it in POLITENESS }) return null
        return tokens.drop(from).joinToString(" ").replaceFirstChar { it.uppercaseChar() }
    }

    /** A token as compared: lowercase, with the punctuation round it stripped; "I'm" stays whole. */
    private fun bare(token: String) = token.lowercase().trim { !it.isLetterOrDigit() }

    /** The first place, at index 1 or later, where the recipient is said; null if nowhere. */
    private fun recipientRun(said: List<String>, recipient: String): IntRange? {
        val isNumber = recipient.any(Char::isDigit) && recipient.none(Char::isLetter)
        for (start in 1 until said.size) {
            val end = if (isNumber) numberEnd(said, start, recipient) else nameEnd(said, start, recipient)
            if (end != null) return start..end
        }
        return null
    }

    /** The last index of the recipient's words in order from [start], or null if they are not there. */
    private fun nameEnd(said: List<String>, start: Int, recipient: String): Int? {
        val words = recipient.lowercase().split(WORD).filter { it.isNotEmpty() }
        val fits = words.isNotEmpty() && start + words.size <= said.size &&
            words.indices.all { said[start + it] == words[it] }
        return if (fits) start + words.size - 1 else null
    }

    /**
     * The last index of the shortest run of tokens from [start] that bear digits and whose digits
     * join up to the number, a leading 1 the model added allowed, as in [ContactMatch.numberSaid].
     */
    private fun numberEnd(said: List<String>, start: Int, number: String): Int? {
        val digits = number.filter(Char::isDigit)
        val wanted = setOf(digits, digits.takeIf { it.length == WITH_COUNTRY_CODE }?.removePrefix("1") ?: digits)
        var heard = ""
        for (at in start until said.size) {
            val here = said[at].filter(Char::isDigit)
            if (here.isEmpty() || heard.length + here.length > digits.length) return null
            heard += here
            if (heard in wanted) return at
        }
        return null
    }

    /** Where the words start: past an optional "a text", "a quick message" or "a text message", then a joiner. */
    private fun messageStart(said: List<String>, from: Int): Int {
        var at = from
        if (said.getOrNull(at) in ARTICLES) {
            val noun = if (said.getOrNull(at + 1) == "quick") at + 2 else at + 1
            val nouns = said.drop(noun).takeWhile { it in MESSAGE_NOUNS }.size
            if (nouns > 0) at = noun + nouns
        }
        val joiner = JOINERS.firstOrNull { said.drop(at).take(it.size) == it }
        return at + (joiner?.size ?: 0)
    }
}
