/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.stt

/**
 * Recognises a transcript Whisper made out of something other than a command.
 *
 * Whisper does not return nothing. Given music, a television or a quiet room, base.en falls
 * back on what ends the videos it learned from — "you", "Thank you.", "Thanks for watching!",
 * a lone "♪" — or on a stray interjection. Those reached the router as commands, and the
 * small model turned them into alarms for the current minute. None of them is a command on
 * its own, so a transcript that is nothing more than one of them counts as hearing nothing.
 *
 * Only the whole transcript is judged: "thank you" after "set an alarm for seven" is
 * politeness, and the command around it still runs.
 */
object JunkTranscript {

    /**
     * What Whisper writes for non-speech, and the lone interjections that reached the router
     * as commands. Compared after normalising, so "Thank you." and "THANK YOU!" are one entry.
     */
    private val PHRASES = setOf(
        "you", "thank you", "thanks for watching", "thank you for watching", "i", "oh", "bye",
        "the end", "so", "uh", "um", "hmm", "ah", "huh", "music", "applause", "silence", "beep",
        "bts", "okay", "ok", "yeah", "hey", "wow", "shh", "ahh",
    )

    /** "[MUSIC]", "(upbeat music)", "[BLANK_AUDIO]": captions for sounds, not words anyone said. */
    private val SOUND_TAG = Regex("""\[[^\]]*]|\([^)]*\)""")

    private val NOT_A_WORD = Regex("""[^\p{L}\p{N}]+""")

    /**
     * True if [transcript] holds no command: blank, nothing but symbols such as "♪" or sound
     * tags, or one of [PHRASES] once case, punctuation and tags are stripped.
     */
    fun isJunk(transcript: String): Boolean {
        val words = transcript.lowercase()
            .replace(SOUND_TAG, " ")
            .split(NOT_A_WORD)
            .filter { it.isNotEmpty() }
        return words.isEmpty() || words.joinToString(" ") in PHRASES
    }
}
