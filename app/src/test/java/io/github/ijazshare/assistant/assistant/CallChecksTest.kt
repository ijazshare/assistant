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
import io.github.ijazshare.assistant.tools.AssistantTools
import io.github.ijazshare.assistant.tools.ToolCall
import org.junit.Test

class CallChecksTest {

    @Test
    fun `read_screen with nothing pointing at the screen becomes unsupported`() {
        val call = ToolCall(AssistantTools.READ_SCREEN, emptyMap())

        val checked = CallChecks.reconciled("what is the capital of France", call)

        assertThat(checked.tool).isEqualTo(AssistantTools.UNSUPPORTED)
    }

    @Test
    fun `a digit recipient that was never said is dropped`() {
        val call = ToolCall(AssistantTools.SEND_MESSAGE, mapOf("recipient" to "5550123", "body" to "running late"))

        val checked = CallChecks.reconciled("send a message saying running late", call)

        assertThat(checked.arguments).doesNotContainKey("recipient")
    }

    @Test
    fun `a digit recipient the session chose, carrying a display name, is kept`() {
        val call = ToolCall(
            AssistantTools.SEND_MESSAGE,
            mapOf("recipient" to "5550123", "display" to "Sam Lee", "body" to "running late"),
        )

        val checked = CallChecks.reconciled("tell Sam running late", call)

        assertThat(checked.arguments["recipient"]).isEqualTo("5550123")
    }

    @Test
    fun `a tap label that was never said is dropped`() {
        val call = ToolCall(AssistantTools.TAP_TEXT, mapOf("label" to "Delete"))

        val checked = CallChecks.reconciled("tap the menu", call)

        assertThat(checked.arguments).doesNotContainKey("label")
    }

    @Test
    fun `a message for another app is declined`() {
        val call = ToolCall(AssistantTools.SEND_MESSAGE, mapOf("recipient" to "Sam", "body" to "hello"))

        val checked = CallChecks.reconciled("Send a signal message to Sam", call)

        assertThat(checked.tool).isEqualTo(AssistantTools.UNSUPPORTED)
    }

    @Test
    fun `an app named after the contact is still declined`() {
        listOf("Send Sam a message on WhatsApp saying hi", "Text Sam on what's app that I'm late").forEach {
            val call = ToolCall(AssistantTools.SEND_MESSAGE, mapOf("recipient" to "Sam"))

            val checked = CallChecks.reconciled(it, CallChecks.withMessageFromWords(it, call))

            assertThat(checked.tool).isEqualTo(AssistantTools.UNSUPPORTED)
        }
    }

    @Test
    fun `a message body is set from the words said, not the model's`() {
        val transcript = "Text Sam I'm running late"
        val call = ToolCall(AssistantTools.SEND_MESSAGE, mapOf("recipient" to "Sam", "body" to "hello how are you"))

        val checked = CallChecks.withMessageFromWords(transcript, call)

        assertThat(checked.arguments["body"]).isEqualTo("I'm running late")
        assertThat(checked.arguments["recipient"]).isEqualTo("Sam")
    }

    @Test
    fun `a message call with no message loses its body`() {
        val transcript = "Text me to check the email"
        val call = ToolCall(AssistantTools.SEND_MESSAGE, mapOf("recipient" to "Sam", "body" to transcript))

        val checked = CallChecks.withMessageFromWords("Text Sam please", call)

        assertThat(checked.arguments).doesNotContainKey("body")
        assertThat(checked.arguments["recipient"]).isEqualTo("Sam")
        // And a call the model made with no body at all gets one when words were said.
        val bare = ToolCall(AssistantTools.SEND_MESSAGE, mapOf("recipient" to "me"))
        assertThat(CallChecks.withMessageFromWords(transcript, bare).arguments["body"]).isEqualTo("Check the email")
    }

    @Test
    fun `other tools are left alone by the message words`() {
        val call = ToolCall(AssistantTools.CALL_CONTACT, mapOf("recipient" to "Sam"))

        assertThat(CallChecks.withMessageFromWords("Call Sam", call)).isEqualTo(call)
    }

    @Test
    fun `a body that is the request is dropped`() {
        val call = ToolCall(AssistantTools.SEND_MESSAGE, mapOf("recipient" to "Sam", "body" to "Text Sam please"))

        val checked = CallChecks.reconciled("Text Sam please", call)

        assertThat(checked.arguments).doesNotContainKey("body")
        assertThat(checked.arguments["recipient"]).isEqualTo("Sam")
    }

    @Test
    fun `a timer needs a length said, or the word timer`() {
        // A sum the calculator could not read: the small model made a 20 minute 39 second timer of it.
        val sum = ToolCall(AssistantTools.SET_TIMER, mapOf("minutes" to "20", "seconds" to "39"))
        assertThat(CallChecks.reconciled("20 plus 390x", sum).arguments).isEmpty()
        val asked = ToolCall(AssistantTools.SET_TIMER, mapOf("minutes" to "3"))
        assertThat(CallChecks.reconciled("Set a timer for 3 minutes", asked)).isEqualTo(asked)
        // Misheard, but the unit was said.
        val misheard = ToolCall(AssistantTools.SET_TIMER, mapOf("minutes" to "10"))
        assertThat(CallChecks.reconciled("I don't mind you for 10 minutes", misheard)).isEqualTo(misheard)
    }

    @Test
    fun `navigate goes only where it was told to`() {
        val home = ToolCall(AssistantTools.NAVIGATE, mapOf("target" to "home"))
        assertThat(CallChecks.reconciled("Navigate to work", home).arguments).doesNotContainKey("target")
        assertThat(CallChecks.reconciled("go home", home)).isEqualTo(home)
        val quick = ToolCall(AssistantTools.NAVIGATE, mapOf("target" to "quick_settings"))
        assertThat(CallChecks.reconciled("How far is New York from Chicago?", quick).arguments)
            .doesNotContainKey("target")
        assertThat(CallChecks.reconciled("open quick settings", quick)).isEqualTo(quick)
    }
}
