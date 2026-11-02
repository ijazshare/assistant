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

import android.util.Log
import io.github.ijazshare.assistant.history.QuerySource
import io.github.ijazshare.assistant.history.Resolution
import io.github.ijazshare.assistant.settings.AssistantSettings
import io.github.ijazshare.assistant.tasks.TaskMigration
import io.github.ijazshare.assistant.tasks.TaskOption
import io.github.ijazshare.assistant.tasks.TaskRequest
import io.github.ijazshare.assistant.tasks.TaskResponse
import io.github.ijazshare.assistant.tasks.TaskRouting
import io.github.ijazshare.assistant.tools.AssistantTools
import io.github.ijazshare.assistant.tools.Contact
import io.github.ijazshare.assistant.tools.ContactResolution
import io.github.ijazshare.assistant.tools.ToolCall
import io.github.ijazshare.assistant.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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

// ---- Tasks (spec 4.7, 4.8, 4.12 step 5) -----------------------------------------------------------------

/** The conversation Tasks is waiting on: its id, the question number (1 or 2) and when it expires. */
internal data class TaskPending(val conversationId: String, val round: Int, val expiresAtMillis: Long)

private const val NOT_MINE = "I cannot do that yet."
private const val MAX_TASK_ROUNDS = 2
private val migrating = AtomicBoolean(false)

/**
 * The answer to Tasks' open question, then an undo, then words that are plainly for Tasks (all before the model).
 * [viaTasks] is false when Tasks has just said the words are not its own, so they are not offered a second time.
 */
internal suspend fun VoiceSession.answeredByTasks(transcript: String, sttMillis: Long, viaTasks: Boolean): Boolean {
    val waiting = taskPending
    taskPending = null
    if (waiting != null && waiting.expiresAtMillis > System.currentTimeMillis()) {
        return followUp(transcript, null, waiting, sttMillis)
    }
    if (!viaTasks) return false
    if (TaskRouting.isUndo(transcript) && taskClient.canUndo()) return undo(transcript, sttMillis)
    val recent = System.currentTimeMillis() < taskRecentUntil
    return (recent || TaskRouting.claims(transcript)) &&
        sendToTasks(transcript, "keyword", sttMillis, 0, Resolution.CACHE)
}

/** Sends the words to Tasks and shows what it says. False only for a keyword or follow-up that Tasks did not claim. */
@Suppress("LongParameterList") // the command's timing and how it was resolved travel together
internal suspend fun VoiceSession.sendToTasks(
    transcript: String,
    routedBy: String,
    sttMillis: Long,
    llmMillis: Long,
    resolution: Resolution,
    waiting: TaskPending? = null,
    optionId: String? = null,
): Boolean {
    val request = TaskRequest(
        type = if (waiting == null) "utterance" else "follow_up",
        utterance = transcript.takeIf { optionId == null },
        conversationId = waiting?.conversationId,
        optionId = optionId,
        source = if (source == QuerySource.TYPED) "typed" else "voice",
        routedBy = routedBy,
        round = waiting?.round ?: 0,
        speechEndMillis = speechEnd,
    )
    val answer = taskClient.send(request)
    if (answer.status == "not_mine") {
        if (routedBy != "model") return false
        showTasks(transcript, ToolResult.failed(NOT_MINE), answer, sttMillis, llmMillis, resolution, waiting)
        return true
    }
    showTasks(transcript, answer.asResult(), answer, sttMillis, llmMillis, resolution, waiting)
    return true
}

private fun TaskResponse.asResult() = ToolResult(
    spoken = speech.ifBlank { if (status == "done") "Done." else "Something went wrong in the task app." },
    success = status == "done",
    detail = display?.lines?.joinToString("\n")?.ifBlank { null },
)

@Suppress("LongParameterList") // the command's timing and how it was resolved travel together
private suspend fun VoiceSession.showTasks(
    transcript: String,
    result: ToolResult,
    answer: TaskResponse,
    sttMillis: Long,
    llmMillis: Long,
    resolution: Resolution,
    waiting: TaskPending?,
) {
    val round = (waiting?.round ?: 0) + 1
    taskRecentUntil = System.currentTimeMillis() + TaskRouting.FOLLOW_UP_MS
    // A question Tasks asked a third time is its mistake, not ours to follow: it ends the conversation.
    val asking = answer.conversation?.takeIf { answer.status == "needs_input" && round <= MAX_TASK_ROUNDS }
    asking?.let { taskPending = TaskPending(it.id, round, it.expiresEpochMs) }
    present(
        transcript,
        ToolCall(AssistantTools.TASKS, emptyMap()),
        result,
        sttMillis,
        llmMillis,
        resolution,
        listenAfter = asking != null,
        question = asking?.let { SessionState.FollowUp(result.spoken, it.options) },
        canUndo = taskClient.canUndo(),
    )
}

private suspend fun VoiceSession.followUp(
    transcript: String,
    optionId: String?,
    waiting: TaskPending,
    sttMillis: Long,
): Boolean {
    // A "not mine" answer to a follow-up is a fresh command for the normal path (4.7).
    if (sendToTasks(transcript, "follow_up", sttMillis, 0, Resolution.CACHE, waiting, optionId)) return true
    act(transcript, sttMillis, viaTasks = false)
    return true
}

/** The Undo chip, or the words "undo" with a token held. */
internal suspend fun VoiceSession.undo(transcript: String, sttMillis: Long): Boolean {
    val token = taskClient.lastUndo?.token ?: return false
    val answer = taskClient.send(TaskRequest("undo", undoToken = token, source = "voice", routedBy = "keyword"))
    showTasks(transcript, answer.asResult(), answer, sttMillis, 0, Resolution.CACHE, null)
    return true
}

/** A tapped option chip answers Tasks' question without speech. */
fun VoiceSession.chooseOption(option: TaskOption) {
    val waiting = taskPending ?: return
    taskPending = null
    stopListening()
    requestAudioFocus()
    beginCommand()
    processing = scope.launch { followUp(option.label, option.id, waiting, sttMillis = 0) }
}

/** The Undo chip. */
fun VoiceSession.undoLast() {
    stopListening()
    requestAudioFocus()
    beginCommand()
    processing = scope.launch { undo("undo", sttMillis = 0) }
}

/** Nothing was said, or the overlay closed: tell Tasks to drop its open question (4.7). Nothing is spoken. */
internal fun VoiceSession.cancelTaskQuestion() {
    val waiting = taskPending ?: return
    taskPending = null
    scope.launch { taskClient.send(TaskRequest("cancel", conversationId = waiting.conversationId)) }
}

/** Moves tasks.md into Tasks once, in the background, at the first session after Tasks answers (4.13). */
internal fun VoiceSession.migrateOnce() {
    if (!migrating.compareAndSet(false, true)) return
    scope.launch(Dispatchers.IO) {
        try {
            if (!AssistantSettings(context).tasksMigratedNow()) Log.i("Assistant", TaskMigration(context).run())
        } finally {
            migrating.set(false)
        }
    }
}
