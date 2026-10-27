/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.stt

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class JunkTranscriptTest {

    private fun assertJunk(vararg transcripts: String) {
        for (text in transcripts) assertWithMessage("[$text]").that(JunkTranscript.isJunk(text)).isTrue()
    }

    private fun assertKept(vararg transcripts: String) {
        for (text in transcripts) assertWithMessage("[$text]").that(JunkTranscript.isJunk(text)).isFalse()
    }

    @Test
    fun dropsWhatWhisperWritesForMusicAndNoise() {
        // What base.en writes for music, a television or a quiet room. Routed, these became
        // stray alarms.
        assertJunk("you", "You.", "Thank you.", "THANK YOU!", "Thanks for watching!", "Thank you for watching.")
        assertJunk("The end.", "Bye.", "Music", "Applause.", "Silence", "BEEP")
    }

    @Test
    fun dropsALoneInterjection() {
        assertJunk("I", "Oh", "Oh.", "So", "Uh", "Um.", "Hmm...", "Ah", "Huh?", "Ahh", "Shh!")
        assertJunk("BTS", "Okay.", "OK", "Yeah.", "Hey!", "Wow.")
    }

    @Test
    fun dropsSymbolsAndSoundTags() {
        // No letter or digit at all, or only Whisper's captions for sounds.
        assertJunk("", "   ", "♪", "♪ ♪", "...", "-")
        assertJunk("[MUSIC]", "[BLANK_AUDIO]", "(upbeat music)", "[ Silence ]", "♪ Music ♪", "*beep*")
        assertJunk("Thank you. [MUSIC]")
    }

    @Test
    fun keepsACommand() {
        assertKept("Set an alarm for 7.", "Wake me up at six thirty.", "Call Mum.", "7")
        // A lone "I" is noise; a sentence that starts with one is not.
        assertKept("I need an alarm at seven.")
    }

    @Test
    fun keepsACommandWrappedInPoliteness() {
        // Only the whole transcript is judged, so a junk phrase around a command is harmless.
        assertKept("Thank you, set a timer for ten minutes.", "Okay, call Mum.", "Hey, what time is it?")
        assertKept("[MUSIC] Set a timer for 5 minutes.")
    }
}
