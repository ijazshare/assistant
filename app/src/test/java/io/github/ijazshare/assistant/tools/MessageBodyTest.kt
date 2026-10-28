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
    fun aMessageForAnotherAppIsNotAText() {
        assertThat(
            MessageBody.namesAnotherApp("Send a signal message to Sam", "Send a signal message to Sam", "Sam"),
        ).isTrue()
        assertThat(MessageBody.namesAnotherApp("WhatsApp Yusuf", "What's up?", "Yusuf")).isTrue()
        assertThat(MessageBody.namesAnotherApp("message Sam on Telegram I'm late", "I'm late", "Sam")).isTrue()
        assertThat(MessageBody.namesAnotherApp("email Margaret", "", "Margaret")).isTrue()
        // As Whisper spells them, echoed back as the body, or named twice.
        assertThat(
            MessageBody.namesAnotherApp("Send a what's app message to Sam", "Send a what's app message to Sam", "Sam"),
        ).isTrue()
        assertThat(MessageBody.namesAnotherApp("e-mail Margaret", "", "Margaret")).isTrue()
        assertThat(MessageBody.namesAnotherApp("message Sam on Telegram", "message Sam on Telegram", "Sam")).isTrue()
        assertThat(MessageBody.namesAnotherApp("message Sam on messenger", "message Sam on messenger", "Sam")).isTrue()
        assertThat(
            MessageBody.namesAnotherApp(
                "WhatsApp Yusuf the whatsapp group is dead",
                "the whatsapp group is dead",
                "Yusuf",
            ),
        ).isTrue()
        // Said inside the message itself, it is just a word.
        assertThat(MessageBody.namesAnotherApp("text Sam check your email", "check your email", "Sam")).isFalse()
        assertThat(
            MessageBody.namesAnotherApp("text Sam the signal is bad here", "The signal is bad here.", "Sam"),
        ).isFalse()
        assertThat(MessageBody.namesAnotherApp("text Sam I sent you an email", "I sent you an email", "Sam")).isFalse()
        assertThat(MessageBody.namesAnotherApp("text Sam I'm late", "I'm late", "Sam")).isFalse()
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

    @Test
    fun `the words are what follows the contact's name`() {
        assertThat(MessageBody.fromWords("Text Sam I'm running late", "Sam")).isEqualTo("I'm running late")
        assertThat(MessageBody.fromWords("Text Sam that the meeting moved", "Sam")).isEqualTo("The meeting moved")
        assertThat(MessageBody.fromWords("Send a message to Sam saying hi", "Sam")).isEqualTo("Hi")
        assertThat(MessageBody.fromWords("Send Sam a text saying I'm late", "Sam")).isEqualTo("I'm late")
        assertThat(MessageBody.fromWords("Text me to check the email", "me")).isEqualTo("Check the email")
        assertThat(MessageBody.fromWords("Tell Sam to call me", "Sam")).isEqualTo("Call me")
        assertThat(MessageBody.fromWords("Text Sam Lee that I'm late.", "Sam Lee")).isEqualTo("I'm late.")
    }

    @Test
    fun `a name inside the message is not the recipient`() {
        assertThat(MessageBody.fromWords("Text Will will you grab milk", "Will")).isEqualTo("Will you grab milk")
        assertThat(MessageBody.fromWords("Tell Sam me and Ali are late", "Sam")).isEqualTo("Me and Ali are late")
    }

    @Test
    fun `my before the contact is part of the request`() {
        assertThat(MessageBody.fromWords("Can you text my wife I'm on my way", "wife")).isEqualTo("I'm on my way")
        assertThat(
            MessageBody.fromWords("Send a message to my assistant that everything seems to be working", "Assistant"),
        ).isEqualTo("Everything seems to be working")
    }

    @Test
    fun `a number said in digits is the recipient`() {
        assertThat(MessageBody.fromWords("Text 555 0123 I'm outside", "5550123")).isEqualTo("I'm outside")
        // A leading 1 the model added.
        assertThat(MessageBody.fromWords("Text 555 0123456 I'm outside", "15550123456")).isEqualTo("I'm outside")
    }

    @Test
    fun `no message gives null`() {
        assertThat(MessageBody.fromWords("Text Sam", "Sam")).isNull()
        assertThat(MessageBody.fromWords("Text Sam please", "Sam")).isNull()
        assertThat(MessageBody.fromWords("Text Sam for me", "Sam")).isNull()
        assertThat(MessageBody.fromWords("Text Sam real quick", "Sam")).isNull()
        assertThat(MessageBody.fromWords("Text Sam a message", "Sam")).isNull()
        assertThat(MessageBody.fromWords("Text my assistant", "Assistant")).isNull()
    }

    @Test
    fun `anything but the plain shape gives null`() {
        assertThat(MessageBody.fromWords("Let Sam know I'm late", "Sam")).isNull()
        assertThat(MessageBody.fromWords("Sam I'm late", "Sam")).isNull()
        assertThat(MessageBody.fromWords("Text Bob I'm late", "Sam")).isNull()
        assertThat(MessageBody.fromWords("Text Sam I'm late", "")).isNull()
    }
}
