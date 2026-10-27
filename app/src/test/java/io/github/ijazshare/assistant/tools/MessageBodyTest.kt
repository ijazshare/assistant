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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageBodyTest {

    @Test
    fun keepsADictatedMessage() {
        assertThat(MessageBody.isTraceable("text Sarah I am on my way", "I am on my way")).isTrue()
    }

    @Test
    fun keepsAParaphrasedContraction() {
        // "I'm" heard, "I am" written: most words still line up.
        assertThat(MessageBody.isTraceable("text Dad I'm running late", "I am running late")).isTrue()
    }

    @Test
    fun dropsAnInventedBody() {
        // The bug from testing: a bare "text Dad" produced "hello how are you".
        assertThat(MessageBody.isTraceable("text Dad", "hello how are you")).isFalse()
    }

    @Test
    fun allowsATinyReply() {
        // Nothing to fabricate in "ok"; do not block it.
        assertThat(MessageBody.isTraceable("reply ok", "ok")).isTrue()
    }

    @Test
    fun theRecipientsNameIsNotEvidence() {
        // "Text Hasan" must not go out as "Hello Hasan".
        assertThat(MessageBody.isTraceable("Text Hasan", "Hello Hasan", "Hasan")).isFalse()
        assertThat(MessageBody.isTraceable("Text Hasan", "Hasan", "Hasan")).isFalse()
        assertThat(MessageBody.isTraceable("Text Hasan I'm on my way", "I'm on my way", "Hasan")).isTrue()
        // Nor is anything hidden inside it: "hi" is in "Ibrahim".
        assertThat(MessageBody.isTraceable("Text Ibrahim", "Hi Ibrahim", "Ibrahim")).isFalse()
        assertThat(MessageBody.isTraceable("Text my brother", "Hey bro", "my brother")).isFalse()
    }

    @Test
    fun theCommandItselfIsNotEvidence() {
        // The model echoes the command as the message: "Text Hasan" would be sent to Hasan.
        assertThat(MessageBody.isTraceable("Text Hasan", "Text Hasan", "Hasan")).isFalse()
        assertThat(MessageBody.isTraceable("Text Fatima that", "Text Fatima that", "Fatima")).isFalse()
        assertThat(MessageBody.isTraceable("Tell Sara that I'm late", "that I'm late", "Sara")).isTrue()
        assertThat(MessageBody.isTraceable("Text Mum I'll text you later", "I'll text you later", "Mum")).isTrue()
        assertThat(MessageBody.isTraceable("Send an email to Margaret", "Send an email", "Margaret")).isFalse()
        assertThat(MessageBody.isTraceable("send John Smith an email", "Email", "John Smith")).isFalse()
        assertThat(MessageBody.isTraceable("WhatsApp Yusuf", "What's up?", "Yusuf")).isFalse()
        assertThat(MessageBody.isTraceable("Message my brother", "Message my brother", "brother")).isFalse()
        assertThat(MessageBody.isTraceable("Text Dad the car is ready", "The car is ready", "Dad")).isTrue()
    }

    @Test
    fun dropsABodyAboutSomethingElse() {
        assertThat(MessageBody.isTraceable("message mum", "the meeting is at three")).isFalse()
    }

    @Test
    fun aShortWordIsNotFoundInsideALongerOne() {
        // "ok" is inside "okay", and "in" inside "running": neither was said.
        assertThat(MessageBody.isTraceable("okay text Dad please", "ok", "Dad")).isFalse()
        assertThat(MessageBody.isTraceable("text Dad running", "in on", "Dad")).isFalse()
        // A longer word still matches its own inflection.
        assertThat(MessageBody.isTraceable("text Dad running later", "running late", "Dad")).isTrue()
    }

    @Test
    fun aRequestToSendIsNotTheMessage() {
        // The bug: "Now send that message to Hassan IT" was itself sent to Hassan IT.
        assertThat(MessageBody.isRequest("Now send that message to Hassan IT", "Hassan IT")).isTrue()
        assertThat(MessageBody.isRequest("Now send that message to Sam Lee", "Sam Lee")).isTrue()
        assertThat(MessageBody.isRequest("send it to Sam", "Sam")).isTrue()
        assertThat(MessageBody.isRequest("text Sam", "Sam")).isTrue()
        assertThat(MessageBody.isRequest("send Sam a message", "Sam")).isTrue()
        assertThat(MessageBody.isRequest("Can you please text him", "Sam")).isTrue()
    }

    @Test
    fun dictationIsNotARequest() {
        assertThat(MessageBody.isRequest("I'll be late", "Sam")).isFalse()
        assertThat(MessageBody.isRequest("I'm on my way", "Sam")).isFalse()
        assertThat(MessageBody.isRequest("send me the report", "Sam")).isFalse()
        assertThat(MessageBody.isRequest("I sent the file to Omar", "Sam")).isFalse()
        // Whole sentences too, if the model ever hands one back.
        assertThat(MessageBody.isRequest("text Sam I'll be late", "Sam")).isFalse()
        assertThat(MessageBody.isRequest("send a message to Sam saying I'm on my way", "Sam")).isFalse()
        assertThat(MessageBody.isRequest("tell Sam to send me the report", "Sam")).isFalse()
        assertThat(MessageBody.isRequest("message Sam that I sent the file to Omar", "Sam")).isFalse()
    }

    @Test
    fun `a request with common filler is still a request`() {
        listOf(
            "Send a quick message to Sam",
            "Can you send a text to Sam for me",
            "Hey, text Sam",
            "Send Sam a message right now",
            "Send a real quick text to Sam",
        ).forEach { assertThat(MessageBody.isRequest(it, "Sam")).isTrue() }
        // Filler does not make dictation a request.
        assertThat(MessageBody.isRequest("send me the report for tomorrow", "Sam")).isFalse()
    }

    @Test
    fun `a message to me is dictation, not a request`() {
        listOf("Text me when you land", "Message me", "Text me right now", "Send me that")
            .forEach { assertThat(MessageBody.isRequest(it, "Sam")).isFalse() }
        // "For me" is still only politeness.
        assertThat(MessageBody.isRequest("Can you send a text to Sam for me", "Sam")).isTrue()
    }
}
