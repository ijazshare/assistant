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

import io.github.ijazshare.assistant.history.QuerySource
import io.github.ijazshare.assistant.history.Resolution
import io.github.ijazshare.assistant.tools.AssistantTools
import io.github.ijazshare.assistant.tools.Contact
import io.github.ijazshare.assistant.tools.ContactResolution
import io.github.ijazshare.assistant.tools.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * If a send is waiting for its words, this transcript is them, or a change of mind. If one is
 * waiting for its yes, this is the yes.
 *
 * Ahead of the cache and the router: "I'll be there at six" is a message here, and
 * would be a reminder anywhere else. Used once, expired or not, so the transcript after
 * it is a command again.
 */
internal suspend fun VoiceSession.answeredPendingSend(transcript: String, sttMillis: Long): Boolean {
    val now = System.currentTimeMillis()
    val waiting = PendingSend.take(now) ?: return false
    val send = ToolCall(
        AssistantTools.SEND_MESSAGE,
        mapOf(
            "recipient" to waiting.number,
            "display" to waiting.display,
            "body" to (waiting.body ?: transcript.trim()),
        ),
    )
    var readBack = false
    val call = when {
        waiting.body != null ->
            if (PendingSend.isYes(transcript)) {
                send
            } else {
                ToolCall(AssistantTools.ANSWER, mapOf("text" to "Okay, I didn't send it."))
            }

        PendingSend.isCancel(transcript, waiting.display) ->
            ToolCall(AssistantTools.ANSWER, mapOf("text" to "Okay, I won't send it."))

        // "No, Bob": the name was misheard, and the correction is not a message for the first contact.
        PendingSend.isCorrection(transcript, waiting.display) ->
            ToolCall(AssistantTools.ANSWER, mapOf("text" to "Okay, I won't send it. Say the whole command again."))

        // "Text Bob please" is an order that landed in the answer slot, and "Text me when you land" is
        // dictation; which one it is cannot be told from the words, so it is read back, not sent.
        PendingSend.startsWithVerb(transcript) -> {
            val reply = transcript.trim()
            readBack =
                PendingSend.holdUnlessDismissed(waiting.copy(body = reply, askedAtMillis = now), dismissalsAtStart)
            ToolCall(AssistantTools.ANSWER, mapOf("text" to "Send \"$reply\" to ${waiting.display}?"))
        }

        else -> send
    }
    // A read-back answered yes sends the words that were read back, so they are checked against
    // the dictation they came from: checked against the "yes", every word looks invented and is dropped.
    val said = waiting.body?.takeIf { call === send } ?: transcript
    // Resolution.CACHE: no model was involved, and nothing here is learned.
    carryOut(said, call, sttMillis, llmMillis = 0, resolution = Resolution.CACHE)
    if (readBack) listenForAnswer()
    return true
}

/**
 * If a reminder is waiting for what it is about, this transcript is the answer, or a change of
 * mind. The answer completes the request it was asked for ("Set a reminder" and then "to book
 * tickets at 6 pm"), and the two run as the one command they make, time and all.
 */
internal suspend fun VoiceSession.answeredPendingReminder(transcript: String, sttMillis: Long): Boolean {
    val waiting = PendingReminder.take(System.currentTimeMillis()) ?: return false
    if (PendingSend.isCancel(transcript)) {
        // Resolution.CACHE: no model was involved, and nothing here is learned.
        val call = ToolCall(AssistantTools.ANSWER, mapOf("text" to "Okay, no reminder."))
        carryOut(transcript, call, sttMillis, llmMillis = 0, resolution = Resolution.CACHE)
    } else {
        act("${waiting.request.trim().trimEnd('.', '!', '?')} ${transcript.trim()}", sttMillis)
    }
    return true
}

/**
 * Notes who a "What should I say to X?" was asked for, if X is one person.
 *
 * An unknown name or one that matched several (the contact chooser has run by now, and
 * what it picked arrives here as a number) gets no pending send: the next words are not
 * obviously for anyone, so they are a command.
 */
internal suspend fun VoiceSession.holdSendFor(call: ToolCall, dismissalsBefore: Int): Boolean {
    val spoken = call.string("recipient") ?: return false
    val number = withContext(Dispatchers.IO) { contacts.resolveNumber(spoken) } ?: return false
    val send = PendingSend(number, call.string("display") ?: spoken, System.currentTimeMillis())
    // Not if the owner dismissed the overlay since the command began.
    return PendingSend.holdUnlessDismissed(send, dismissalsBefore)
}

/**
 * The user picked one of several matching contacts. Carry the held command out to them,
 * with the number to send to and the name to say back.
 */
fun VoiceSession.chooseContact(contact: Contact) {
    val pending = pendingChoice ?: return
    pendingChoice = null
    val chosen = pending.call.copy(
        arguments = pending.call.arguments + mapOf("recipient" to contact.number, "display" to contact.name),
    )
    processing = scope.launch {
        carryOut(pending.transcript, chosen, pending.sttMillis, pending.llmMillis, pending.resolution)
    }
}

/**
 * The contacts to choose between, if a messaging or calling command's recipient matched
 * several people — null for any other tool or an unambiguous name. Reads the contacts,
 * SMS and call logs off the main thread, ordered so the person most recently texted or
 * called is first (and so survives the cap).
 */
internal suspend fun VoiceSession.contactChoices(call: ToolCall): List<Contact>? {
    val messaging = call.tool == AssistantTools.SEND_MESSAGE || call.tool == AssistantTools.CALL_CONTACT
    val recipient = call.arguments["recipient"]?.takeIf { it.isNotBlank() }
    if (!messaging || recipient == null) return null
    return withContext(Dispatchers.IO) {
        (contacts.resolve(recipient) as? ContactResolution.Many)?.options?.let(recency::sortByRecency)
    }
}

/**
 * Straight back to the microphone, so the owner can answer the question just asked without summoning
 * again. start() is the ordinary summon, and a stop since this command began (the overlay dismissed,
 * a switch to typing) means they did not want it.
 */
internal fun VoiceSession.listenForAnswer() {
    if (source == QuerySource.VOICE && stops == stopsAtStart) start()
}
