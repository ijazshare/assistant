/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The wire format, checked without a network: what goes out, and what is accepted back. */
class RemoteAnswererTest {

    @Test
    fun `request carries the model, the honesty rules and the question`() {
        val body = RemoteAnswerer.requestBody("openai/gpt-4o-mini", "who painted the Mona Lisa", "Admin", "")
        assertThat(body).contains("\"model\":\"openai/gpt-4o-mini\"")
        assertThat(body).contains("\"role\":\"system\"")
        assertThat(body).contains("never claim you did it")
        assertThat(body).contains("\"role\":\"user\"")
        assertThat(body).contains("who painted the Mona Lisa")
        assertThat(body).contains("\"max_tokens\":160")
    }

    @Test
    fun `reply text is the first choice, trimmed`() {
        val body = """
            {"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"  Leonardo da Vinci.  "},
            "finish_reason":"stop"}],"usage":{"total_tokens":12}}
        """.trimIndent()
        assertThat(RemoteAnswerer.replyText(body)).isEqualTo("Leonardo da Vinci.")
    }

    @Test
    fun `no choices, blank content and garbage all mean no answer`() {
        assertThat(RemoteAnswerer.replyText("""{"choices":[]}""")).isNull()
        assertThat(
            RemoteAnswerer.replyText("""{"choices":[{"message":{"role":"assistant","content":"   "}}]}"""),
        ).isNull()
        assertThat(RemoteAnswerer.replyText("not json")).isNull()
    }

    @Test
    fun `notes about the user are included only when present`() {
        assertThat(RemoteAnswerer.systemPrompt("Admin", "")).doesNotContain("About Admin")
        assertThat(
            RemoteAnswerer.systemPrompt("Admin", "Prefers metric units."),
        ).contains("About Admin:\nPrefers metric units.")
    }
}
