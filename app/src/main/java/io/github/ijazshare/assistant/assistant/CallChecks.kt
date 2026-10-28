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
     * A text's words are the user's own, taken from the transcript, never the model's: what it
     * writes as a body is invented or copied, and is replaced here. With
     * no message in the plain shape ("Text Sam", "Text Sam please") there is no body, and the
     * executor asks what to say.
     */
    internal fun withMessageFromWords(transcript: String, call: ToolCall): ToolCall {
        if (call.tool != AssistantTools.SEND_MESSAGE) return call
        val body = MessageBody.fromWords(transcript, call.arguments["recipient"].orEmpty())
        return call.copy(arguments = if (body == null) call.arguments - "body" else call.arguments + ("body" to body))
    }

    /**
     * Checks the model's call against the words it was given: declines a screen read or a phone
     * call nobody asked for, drops a message body that is only the request, and corrects an hour the
     * model converted badly.
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

            call.tool == AssistantTools.CALL_CONTACT -> checkedCall(transcript, call)

            // Dropped, so the executor asks who to text, what to tap, where to go or how long for.
            unsaid.isNotEmpty() -> {
                Log.i(TAG, "dropping $unsaid, never said in [$transcript]")
                call.copy(arguments = call.arguments - unsaid.toSet())
            }

            call.tool == AssistantTools.SEND_MESSAGE -> checkedMessage(transcript, call)

            call.tool == AssistantTools.SET_ALARM || call.tool == AssistantTools.CREATE_REMINDER ->
                withSpokenTime(transcript, withoutInventedTime(transcript, call))

            else -> call
        }
    }

    /**
     * The arguments the user never said: a number to text, words to tap, where to go, or a
     * timer's length. The model fills them whatever was said, and a text to a stranger, a press on
     * the wrong button, a jump to the home screen or a timer nobody asked for cannot be taken back:
     * "Navigate to work" pressed Home, and sums the calculator could not read set real timers. A
     * number the session chose itself (a contact picked from a list, a send held for its words)
     * comes with the contact's name and is not checked.
     */
    private fun unsaid(transcript: String, call: ToolCall): List<String> {
        val words = transcript.lowercase().split(NOT_LETTER).toSet()
        val texted = call.tool == AssistantTools.SEND_MESSAGE
        val number = call.arguments["recipient"]
            ?.takeIf { texted && "display" !in call.arguments && it.any(Char::isDigit) && it.none(Char::isLetter) }
        val label = call.arguments["label"]?.takeIf { call.tool == AssistantTools.TAP_TEXT }
        val target = call.arguments["target"]?.takeIf { call.tool == AssistantTools.NAVIGATE }
        return when {
            number != null && !ContactMatch.numberSaid(transcript, number) -> listOf("recipient")
            label != null && !ScreenQuestion.containsPhrase(transcript, label) -> listOf("label")
            target != null && words.none { it in NAVIGATE_WORDS[target].orEmpty() } -> listOf("target")
            call.tool == AssistantTools.SET_TIMER && words.none { it in TIMER_WORDS } -> TIMER_LENGTH
            else -> emptyList()
        }
    }

    private val NOT_LETTER = Regex("[^a-z]+")

    /**
     * A call rings at once and cannot be taken back, and the model calls whoever is named near a
     * "call": "Tell me to call me back" rang the owner's own phone, and "Text Sam to call me"
     * came back as a call to Sam. So a call is placed only when the whole sentence is a request
     * for one ([CALL_ASKED]), and never to "me", the owner's own number; anything else is declined,
     * never made a text. A contact the session chose from a list comes with its name, and its
     * command was checked before the list was shown.
     */
    private fun checkedCall(transcript: String, call: ToolCall): ToolCall {
        if ("display" in call.arguments) return call
        val recipient = call.arguments["recipient"].orEmpty()
        // The sentence with the person written as "@", to be matched whole.
        val said = transcript.lowercase().split(NOT_WORD).filter { it.isNotEmpty() }
        val run = MessageBody.recipientRun(said, recipient)
        val asked = run != null &&
            CALL_ASKED.matches((said.take(run.first) + "@" + said.drop(run.last + 1)).joinToString(" "))
        val reason = when {
            ContactMatch.isSelf(recipient) -> "I cannot call your own number."
            !asked -> "To make a call, say just \"call\" and the name."
            else -> return call
        }
        Log.i(TAG, "declining call_contact: $reason [$transcript]")
        return ToolCall(AssistantTools.UNSUPPORTED, mapOf("reason" to reason))
    }

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    // The parts of a request for a call, the person written as "@".
    private const val ASKING =
        "(?:(?:please|ok|okay|hey|just|now) )*(?:(?:can|could|would|will) you (?:please |just )*)?"
    private const val CALLING =
        "(?:(?:make|place) a (?:quick )?(?:phone )?)?(?:call|phone|ring|dial)(?: up| back)?(?: to)?(?: my| the)? @"
    private const val GIVING = "give (?:my |the )?@ a (?:call|ring)"
    private const val POLITE =
        "(?: (?:please|now|back|again|up|for me|right now|right away|quickly|thanks|thank you))*"

    /**
     * A request for a call: "Can you please call my @", "Make a quick call to @", "Ring @ back",
     * "Give @ a ring". Not "Tell me to call @", "Remind me to call @", "You can call @", "Don't
     * call @", "Call @ later", "Video call @" or "Call @ and Mum".
     */
    private val CALL_ASKED = Regex("$ASKING(?:$CALLING|$GIVING)$POLITE")

    /** What has to be said for each place navigate can go: "quick" for quick settings, not "settings". */
    private val NAVIGATE_WORDS = mapOf(
        "back" to setOf("back"),
        "home" to setOf("home"),
        "recents" to setOf("recent", "recents"),
        "notifications" to setOf("notification", "notifications"),
        "quick_settings" to setOf("quick"),
    )

    /** A timer needs its unit, or the word itself, said: numbers alone are not a length. */
    private val TIMER_WORDS = setOf(
        "timer", "timers", "second", "seconds", "sec", "secs", "minute", "minutes", "min", "mins",
        "hour", "hours", "hr", "hrs",
    )
    private val TIMER_LENGTH = listOf("hours", "minutes", "seconds")

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
     * A message body that is the request itself ("Now send that message to Sam") was said, but
     * as the command and not as the message; drop it so the executor asks for the words. The
     * body comes from the transcript ([withMessageFromWords]), so it is never invented. And only
     * texts can be sent: words meant for Signal or WhatsApp are declined rather than sent as an SMS.
     */
    private fun checkedMessage(transcript: String, call: ToolCall): ToolCall {
        val body = call.arguments["body"].orEmpty()
        val recipient = call.arguments["recipient"].orEmpty()
        val named = "$recipient ${call.arguments["display"].orEmpty()}"
        if (MessageBody.namesAnotherApp(transcript, body, named)) {
            Log.i(TAG, "declining a message for another app in [$transcript]")
            return ToolCall(AssistantTools.UNSUPPORTED, mapOf("reason" to "I can only send texts for now."))
        }
        if (body.isNotBlank() && MessageBody.isRequest(body, named)) {
            Log.i(TAG, "dropping body \"$body\", it is the request, for [$transcript]")
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
