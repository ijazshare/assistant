/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tools

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
}
