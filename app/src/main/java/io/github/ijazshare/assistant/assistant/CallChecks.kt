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
import io.github.ijazshare.assistant.tools.AssistantTools
import io.github.ijazshare.assistant.tools.ContactMatch
import io.github.ijazshare.assistant.tools.MessageBody
import io.github.ijazshare.assistant.tools.StatedTime
import io.github.ijazshare.assistant.tools.TimeExpressions
import io.github.ijazshare.assistant.tools.TimeResolver
import io.github.ijazshare.assistant.tools.ToolCall

/** The checks of the model's call against the words it was given. Pure: no session state. */
internal object CallChecks {
    private const val TAG = "Assistant"

    /**
     * The model sometimes copies the whole sentence into a message body: "Text me to check the
     * email" would go out as that. It is not the message, and which words are cannot be told safely
     * ("Text Sam please" is not "Please"), so the body is dropped and the words are asked for.
     */
    internal fun withoutEchoedRequest(transcript: String, call: ToolCall): ToolCall {
        val echoed = call.tool == AssistantTools.SEND_MESSAGE &&
            MessageBody.isEchoed(transcript, call.arguments["body"].orEmpty())
        return if (echoed) call.copy(arguments = call.arguments - "body") else call
    }

    /**
     * Checks the model's call against the words it was given: declines a screen read
     * nobody asked for, drops a message body nobody said, and corrects an hour the model
     * converted badly.
     *
     * The only place the transcript and the resolved call are both in hand, which is
     * what this needs: the executor sees a call and never the sentence behind it.
     */
    internal fun reconciled(transcript: String, call: ToolCall): ToolCall {
        val unsaid = unsaid(transcript, call)
        return when {
            // Nobody asked about the screen, so reading it out answers nothing; a decline at
            // least says so. Unsuccessful, so the guess is never learned either.
            call.tool == AssistantTools.READ_SCREEN && !ScreenQuestion.pointsAtScreen(transcript) -> {
                Log.i(TAG, "declining read_screen, nothing points at the screen in [$transcript]")
                ToolCall(AssistantTools.UNSUPPORTED, mapOf("reason" to "For the screen, say \"read this\"."))
            }

            // Dropped, so the executor asks who to text or what to tap.
            unsaid != null -> {
                Log.i(TAG, "dropping $unsaid \"${call.arguments[unsaid]}\", never said in [$transcript]")
                call.copy(arguments = call.arguments - unsaid)
            }

            call.tool == AssistantTools.SEND_MESSAGE -> checkedMessage(transcript, call)

            call.tool == AssistantTools.SET_ALARM || call.tool == AssistantTools.CREATE_REMINDER ->
                withSpokenTime(transcript, withoutInventedTime(transcript, call))

            else -> call
        }
    }

    /**
     * The argument holding a number to text or call, or words to tap, that the user never said.
     * The model fills both whatever was said, and neither a text to a stranger nor a press on the
     * wrong button can be taken back. A number the session chose itself (a contact picked from a
     * list, a send held for its words) comes with the contact's name and is not checked.
     */
    private fun unsaid(transcript: String, call: ToolCall): String? {
        val dialled = call.tool == AssistantTools.SEND_MESSAGE || call.tool == AssistantTools.CALL_CONTACT
        val number = call.arguments["recipient"]
            ?.takeIf { dialled && "display" !in call.arguments && it.any(Char::isDigit) && it.none(Char::isLetter) }
        val label = call.arguments["label"]?.takeIf { call.tool == AssistantTools.TAP_TEXT }
        return when {
            number != null && !ContactMatch.numberSaid(transcript, number) -> "recipient"
            label != null && !ScreenQuestion.containsPhrase(transcript, label) -> "label"
            else -> null
        }
    }

    /**
     * A reminder with neither a task nor a time said is the model's invention twice over: "set
     * a reminder" came back as one for 9 PM tomorrow. Its time goes, so the executor asks what
     * the reminder is for.
     */
    private fun withoutInventedTime(transcript: String, call: ToolCall): ToolCall {
        val invented = call.tool == AssistantTools.CREATE_REMINDER &&
            TimeExpressions.of(transcript).isEmpty() && StatedTime.isUntitled(call.arguments["task"].orEmpty())
        return if (invented) call.copy(arguments = call.arguments - "hour" - "minute") else call
    }

    /**
     * A message body the user never said is invented, not dictated; drop it so the executor
     * asks for the words rather than sending "hello how are you" to someone. So is one that is
     * the request itself ("Now send that message to Sam"), which the user did say, but as the
     * command and not as the message. And only texts can be sent: words meant for Signal or
     * WhatsApp are declined rather than sent as an SMS.
     */
    private fun checkedMessage(transcript: String, call: ToolCall): ToolCall {
        val body = call.arguments["body"].orEmpty()
        val recipient = call.arguments["recipient"].orEmpty()
        val named = "$recipient ${call.arguments["display"].orEmpty()}"
        if (MessageBody.namesAnotherApp(transcript, body, named)) {
            Log.i(TAG, "declining a message for another app in [$transcript]")
            return ToolCall(AssistantTools.UNSUPPORTED, mapOf("reason" to "I can only send texts for now."))
        }
        if (body.isNotBlank() &&
            (MessageBody.isRequest(body, named) || !MessageBody.isTraceable(transcript, body, recipient))
        ) {
            Log.i(TAG, "dropping invented body \"$body\" for [$transcript]")
            return call.copy(arguments = call.arguments - "body")
        }
        return call
    }

    /**
     * Replaces the model's time with the one the words state: "in 30 minutes" is now plus thirty
     * minutes, and "at 9:35" is 9:35, whatever the model made of them, but only when the transcript
     * states exactly one time. With none or several the model's numbers stand, with only the am/pm
     * correction of the hour it always had.
     */
    private fun withSpokenTime(transcript: String, call: ToolCall): ToolCall {
        // The tools have only today and tomorrow, so the model cannot have a day past tomorrow right.
        if (StatedTime.beyondTomorrow(transcript)) {
            Log.i(TAG, "declining a day past tomorrow in [$transcript]")
            return ToolCall(AssistantTools.UNSUPPORTED, mapOf("reason" to "I can only set that for today or tomorrow."))
        }
        val stated = call.arguments["hour"]?.toIntOrNull()
        val minute = call.arguments["minute"]?.toIntOrNull()
        val spoken = StatedTime.of(transcript, stated, minute) ?: return withReconciledHour(transcript, call, stated)
        val fixes = buildMap {
            if (spoken.hour != stated) put("hour", spoken.hour.toString())
            if (spoken.minute != (minute ?: 0)) put("minute", spoken.minute.toString())
            if (spoken.tomorrow != null && spoken.tomorrow != call.bool("tomorrow")) {
                put("tomorrow", spoken.tomorrow.toString())
            }
        }
        if (fixes.isEmpty()) return call
        Log.i(TAG, "time corrected by $fixes (model said $stated:$minute) for [$transcript]")
        return call.copy(arguments = call.arguments + fixes)
    }

    /** The model's time stands, with only the hour put right against an am, pm or part of day said. */
    private fun withReconciledHour(transcript: String, call: ToolCall, stated: Int?): ToolCall {
        val corrected = stated?.let { TimeResolver.reconcileHour(transcript, it) }
        if (corrected == null || corrected == stated) return call
        Log.i(TAG, "hour corrected from $stated to $corrected for [$transcript]")
        return call.copy(arguments = call.arguments + ("hour" to corrected.toString()))
    }
}
