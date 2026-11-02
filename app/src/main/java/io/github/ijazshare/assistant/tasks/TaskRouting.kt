/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.tasks

/**
 * The deterministic pre-route (spec 4.12 step 3): words that are plainly for Tasks go there without the model,
 * and so does anything said within [FOLLOW_UP_MS] of a Tasks reply (VoiceSession.answeredByTasks).
 * Over-claiming is cheap, because a `not_mine` answer falls back to the normal path. The phrases are tested
 * against the shared fixture `contract/v1/routing.jsonl`.
 */
object TaskRouting {
    private val FILLER =
        Regex("^(actually|okay|ok|and|oh|no|wait|hey|so|right|please|um|uh)[,.!]?\\s+", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("\\b(timer|alarm|wake me)\\b", RegexOption.IGNORE_CASE)
    private val UNDO = Regex("^(undo|undo that|scratch that|take that back)\\b", RegexOption.IGNORE_CASE)
    private val RAMBLE = Regex("\\b(brain ?dump|ramble)\\b", RegexOption.IGNORE_CASE)

    private val CLAIMS = listOf(
        "^remind me\\b",
        "^(add|put) .+ (to|on|in) (my|the) .*\\b(list|tasks?|to-?dos?)\\b",
        "^(add|create|new) (a )?(task|to-?do)\\b",
        "^(mark|tick|check) .+ ((as|has) )?(done|complete|completed|off)\\b",
        "^tick off\\b",
        "^(i['’]ve|i have) (done|finished|completed|paid|sent|called|booked|bought|posted|emailed)\\b",
        "^(what['’]s|what is|what do i have|anything|is there anything)\\b.*" +
            "\\b(on my (list|tasks)|my tasks|to-?do|due|overdue)\\b",
        "^what do i have (today|tomorrow|on)\\b",
        "^(read|show|open) my (tasks|list|to-?do( list)?)\\b",
        "^my (tasks|to-?do( list)?)\\b",
        "^to-?do\\b",
        "^(move|push|reschedule|postpone|snooze) .+ (to|until|for|by)\\b",
        "^(snooze|push|move) (that|it|this)\\b",
        "^(delete|remove) .+ (task|reminder|from my list)\\b",
        "^(create|make|add) (a )?(new )?(list|project|label) (called|named)\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** The transcript without its leading "okay", "so" and the like. */
    fun stripped(transcript: String): String {
        var t = transcript.trim()
        while (true) t = FILLER.replace(t, "").takeIf { it != t } ?: return t
    }

    fun isUndo(transcript: String) = UNDO.containsMatchIn(stripped(transcript))

    fun claims(transcript: String): Boolean {
        val t = stripped(transcript)
        if (CLOCK.containsMatchIn(t)) return false
        val first = t.split(Regex("\\s+")).take(RAMBLE_WORDS).joinToString(" ")
        return CLAIMS.any { it.containsMatchIn(t) } || RAMBLE.containsMatchIn(first)
    }

    private const val RAMBLE_WORDS = 5

    /** After a Tasks reply, the next words go to Tasks first ("actually mark that as done"). */
    const val FOLLOW_UP_MS = 60_000L
}
