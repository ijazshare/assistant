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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PendingSendTest {

    private val asked = PendingSend(number = "+15550100", display = "Sam Lee", askedAtMillis = 1_000_000L)

    @Test
    fun `an answer within a minute is still an answer`() {
        assertThat(asked.expired(1_000_000L + 59_000L)).isFalse()
        assertThat(asked.expired(1_000_000L + PendingSend.WINDOW_MILLIS)).isFalse()
    }

    @Test
    fun `a transcript after a minute is a new command`() {
        assertThat(asked.expired(1_000_000L + PendingSend.WINDOW_MILLIS + 1)).isTrue()
    }

    @Test
    fun `a change of mind is not a message`() {
        listOf("cancel", "Cancel.", "never mind", "Never mind.", "nevermind", "Stop", "forget it", "Forget it!")
            .forEach { assertThat(PendingSend.isCancel(it)).isTrue() }
        assertThat(PendingSend.isCancel("Don't send it.")).isTrue()
        assertThat(PendingSend.isCancel("don’t send it")).isTrue()
    }

    @Test
    fun `a message that merely contains a cancel word is a message`() {
        assertThat(PendingSend.isCancel("Cancel my appointment tomorrow")).isFalse()
        assertThat(PendingSend.isCancel("I'll be late, never mind dinner")).isFalse()
        assertThat(PendingSend.isCancel("stop by the shop on your way")).isFalse()
    }

    @Test
    fun `a dismissal forgets the send, so a later command is not texted`() {
        val now = 1_000_000L
        PendingSend.hold(PendingSend("+15550101", "Sam Lee", now))
        PendingSend.dismiss()
        assertThat(PendingSend.take(now + 30_000L)).isNull()
    }

    @Test
    fun `a held send is used once, and only inside its window`() {
        val now = 1_000_000L
        PendingSend.hold(PendingSend("+15550101", "Sam Lee", now))
        assertThat(PendingSend.take(now + 1_000L)?.display).isEqualTo("Sam Lee")
        assertThat(PendingSend.take(now + 2_000L)).isNull()
        PendingSend.hold(PendingSend("+15550101", "Sam Lee", now))
        assertThat(PendingSend.take(now + PendingSend.WINDOW_MILLIS + 1)).isNull()
    }

    @Test
    fun `the common ways of cancelling are not messages`() {
        listOf(
            "Cancel that.", "No, cancel.", "Cancel it", "Don't send anything", "No.", "Nope",
            "Never mind, don't send it.", "Do not send it", "No thanks, forget it",
        ).forEach { assertThat(PendingSend.isCancel(it)).isTrue() }
    }

    @Test
    fun `a message with words of its own is not a cancel`() {
        assertThat(PendingSend.isCancel("No problem, see you at six")).isFalse()
        assertThat(PendingSend.isCancel("I'm not coming")).isFalse()
        assertThat(PendingSend.isCancel("Thanks")).isFalse()
    }

    @Test
    fun `a dismissal during the command stops its send being held`() {
        val now = 1_000_000L
        // "Text Sam", dismissed while it was still thinking: the question is asked after the dismissal.
        val began = PendingSend.dismissals()
        PendingSend.dismiss()
        assertThat(PendingSend.holdUnlessDismissed(PendingSend("+15550101", "Sam Lee", now), began)).isFalse()
        assertThat(PendingSend.take(now + 1_000L)).isNull()
    }

    @Test
    fun `a send is held when nothing was dismissed since the command began`() {
        val now = 1_000_000L
        val began = PendingSend.dismissals()
        assertThat(PendingSend.holdUnlessDismissed(PendingSend("+15550101", "Sam Lee", now), began)).isTrue()
        assertThat(PendingSend.take(now + 1_000L)?.display).isEqualTo("Sam Lee")
    }

    @Test
    fun `a corrected name is not a message for the first contact`() {
        listOf("No, Bob", "No, I said Bob", "Not Sam, Bob", "No, it's Bob.", "Not Sam", "Nope, Bob Ng")
            .forEach { assertThat(PendingSend.isCorrection(it, "Sam Lee")).isTrue() }
    }

    @Test
    fun `a message that opens with no is still a message, not a correction`() {
        listOf("No problem, see you at six", "No, I'm not coming", "No, Bob is late", "See you at six", "Bob called")
            .forEach { assertThat(PendingSend.isCorrection(it, "Sam Lee")).isFalse() }
    }

    @Test
    fun `wait and other asides around a cancel are still a cancel`() {
        listOf(
            "Wait, cancel",
            "Wait, no",
            "No, I changed my mind",
            "Don't text him",
            "Cancel the message to Sam",
            "Never mind, I'll call him",
            "Hold on",
            "Never mind, see you at six",
        ).forEach { assertThat(PendingSend.isCancel(it, "Sam Lee")).isTrue() }
    }

    @Test
    fun `a message with words of its own is not a cancel even after wait or hold`() {
        listOf("Wait, I'll be there at six", "Hold on, I'm coming", "Cancel my appointment tomorrow")
            .forEach { assertThat(PendingSend.isCancel(it, "Sam Lee")).isFalse() }
    }

    @Test
    fun `short refusals stay cancels, the safe side`() {
        listOf("No thanks", "Please don't", "Don't do it", "Stop it")
            .forEach { assertThat(PendingSend.isCancel(it, "Sam")).isTrue() }
    }

    @Test
    fun `a reply that opens with an order is read back, whoever it is for`() {
        val replies = "Text Bob please|Send it to Bob|Can you text Bob for me?|Text my wife|Text me when you land|" +
            "Please call Bob|Um, could you ring Bob|Actually, tell him I'm late|Call me when you land|" +
            "WhatsApp Bob|Email Bob|DM Bob|phone Bob|Message Bob"
        replies.split('|').forEach { assertThat(PendingSend.startsWithVerb(it)).isTrue() }
    }

    @Test
    fun `any other reply is the message, sent at once`() {
        val replies = "I'll be there at six|See you at six|I'll call you back|I'll text you later|Textbook arrived|" +
            "Thanks for the call|Can you pick up milk"
        replies.split('|').forEach { assertThat(PendingSend.startsWithVerb(it)).isFalse() }
    }

    @Test
    fun `a read-back is answered by a yes and nothing else sends it`() {
        "Yes|yes.|Yeah|Yep|Yup|Sure|Send|Send it|Go ahead|Do it|Correct|OK|Okay.".split('|')
            .forEach { assertThat(PendingSend.isYes(it)).isTrue() }
        "No|Yes but add hello|Text Bob|Maybe|Cancel|I'll be there at six|".split('|')
            .forEach { assertThat(PendingSend.isYes(it)).isFalse() }
    }

    @Test
    fun `a read-back is held for thirty seconds, not sixty`() {
        val now = 1_000_000L
        val held = PendingSend("+15550101", "Sam Lee", now, body = "Text Bob please")
        assertThat(held.expired(now + PendingSend.CONFIRM_MILLIS)).isFalse()
        assertThat(held.expired(now + PendingSend.CONFIRM_MILLIS + 1)).isTrue()
        assertThat(held.copy(body = null).expired(now + PendingSend.CONFIRM_MILLIS + 1)).isFalse()
    }
}
