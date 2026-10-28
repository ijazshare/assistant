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
    fun `a body equal to the whole transcript is dropped as an echo`() {
        val transcript = "Text me to check the email"
        val call = ToolCall(AssistantTools.SEND_MESSAGE, mapOf("recipient" to "me", "body" to transcript))

        val checked = CallChecks.withoutEchoedRequest(transcript, call)

        assertThat(checked.arguments).doesNotContainKey("body")
        assertThat(checked.arguments["recipient"]).isEqualTo("me")
    }

    @Test
    fun `a model body that is the request is dropped`() {
        val call = ToolCall(AssistantTools.SEND_MESSAGE, mapOf("recipient" to "Sam", "body" to "Text Sam please"))

        val checked = CallChecks.reconciled("Text Sam please", call)

        assertThat(checked.arguments).doesNotContainKey("body")
        assertThat(checked.arguments["recipient"]).isEqualTo("Sam")
    }
}
