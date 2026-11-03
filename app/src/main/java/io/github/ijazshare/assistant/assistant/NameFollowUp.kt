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
import io.github.ijazshare.assistant.tools.AssistantTools
import io.github.ijazshare.assistant.tools.Contact
import io.github.ijazshare.assistant.tools.ContactResolution
import io.github.ijazshare.assistant.tools.ToolCall
import io.github.ijazshare.assistant.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The answer to whichever question was just asked: who a name is, the words of a text, or what a reminder is about. */
internal suspend fun VoiceSession.answeredFollowUp(transcript: String, sttMillis: Long): Boolean =
    answeredPendingName(transcript, sttMillis) ||
        answeredPendingSend(transcript, sttMillis) ||
        answeredPendingReminder(transcript, sttMillis)

/**
 * Carries [held] out to [contact], first saving the name that was heard for them to their notes if the
 * owner was told it would be (they chose [contact] for it), and saying that it was saved.
 */
internal suspend fun VoiceSession.rememberAndCarryOut(held: PendingChoice, contact: Contact) {
    val heard = held.remember
    val saved = heard != null && withContext(Dispatchers.IO) { contacts.remember(contact.number, heard) }
    val note = when {
        heard == null -> null
        saved -> "I'll know \"$heard\" as ${contact.name} from now on."
        else -> "I couldn't save \"$heard\" to ${contact.name}'s notes. Allow Contacts again under System access."
    }
    val chosen = held.call.copy(
        arguments = held.call.arguments + mapOf("recipient" to contact.number, "display" to contact.name),
    )
    carryOut(held.transcript, chosen, held.sttMillis, held.llmMillis, held.resolution, before = note)
}

/**
 * A text or call to a name no contact answers to. Asks who it was, with a guess when one contact sounds
 * like it ("Did you mean Bilal?") or a chooser when several do, and holds the command for the answer.
 * The question says the name will be remembered, so the answer is the owner's yes to saving it. False
 * when the name is known, or is not a name (a number, "me"), or contacts cannot be read.
 */
internal suspend fun VoiceSession.askedWhoUnknown(
    transcript: String,
    call: ToolCall,
    sttMillis: Long,
    llmMillis: Long,
    resolution: Resolution,
): Boolean {
    val messaging = call.tool == AssistantTools.SEND_MESSAGE || call.tool == AssistantTools.CALL_CONTACT
    val heard = call.string("recipient")?.takeIf { messaging } ?: return false
    val alike = withContext(Dispatchers.IO) { if (contacts.unknown(heard)) contacts.soundsLike(heard) else null }
        ?: return false
    val held = PendingChoice(transcript, call, sttMillis, llmMillis, resolution, remember = heard)
    if (alike.size > 1) {
        pendingChoice = held
        _state.value = SessionState.ChooseContact(heard, recency.sortByRecency(alike), remember = true)
        return true
    }
    val suggestion = alike.singleOrNull()
    ask(transcript, PendingName(heard, held, suggestion, System.currentTimeMillis()))
    return true
}

private suspend fun VoiceSession.ask(transcript: String, name: PendingName) {
    val held = name.held
    val question =
        name.suggestion?.let { "Did you mean ${it.name}? Say yes, and I'll remember \"${name.heard}\" for them." }
            ?: "Who is \"${name.heard}\"? Say their name, and I'll remember it for them."
    val asking = PendingName.holdUnlessDismissed(name, dismissalsAtStart)
    present(
        transcript,
        held.call,
        ToolResult.failed(question),
        held.sttMillis,
        held.llmMillis,
        held.resolution,
        listenAfter = asking,
    )
}

/** If "Who is ...?" or "Did you mean ...?" was just asked, this transcript is the answer, or a change of mind. */
internal suspend fun VoiceSession.answeredPendingName(transcript: String, sttMillis: Long): Boolean {
    val waiting = PendingName.take(System.currentTimeMillis()) ?: return false
    val said = PendingName.nameIn(transcript)
    val suggestion = waiting.suggestion
    when {
        suggestion != null && PendingSend.isYes(transcript) -> rememberAndCarryOut(waiting.held, suggestion)

        // A bare "no" to the guess: then who was it?
        suggestion != null && said.isEmpty() -> ask(transcript, waiting.copy(suggestion = null))

        said.isEmpty() || PendingSend.isCancel(transcript) -> {
            // Resolution.CACHE: no model was involved, and nothing here is learned.
            val call = ToolCall(AssistantTools.ANSWER, mapOf("text" to "Okay, I won't."))
            carryOut(transcript, call, sttMillis, llmMillis = 0, resolution = Resolution.CACHE)
        }

        else -> when (val found = withContext(Dispatchers.IO) { contacts.resolve(said) }) {
            is ContactResolution.One -> {
                val display = said.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
                rememberAndCarryOut(waiting.held, Contact(display, found.number))
            }

            is ContactResolution.Many -> {
                pendingChoice = waiting.held
                _state.value = SessionState.ChooseContact(said, recency.sortByRecency(found.options), remember = true)
            }

            ContactResolution.None -> present(
                transcript,
                waiting.held.call,
                ToolResult.failed("I couldn't find $said either. Say the whole command again."),
                sttMillis,
                llmMillis = 0,
                resolution = Resolution.CACHE,
            )
        }
    }
    return true
}
