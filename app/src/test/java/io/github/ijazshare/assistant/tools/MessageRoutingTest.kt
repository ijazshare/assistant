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

class MessageRoutingTest {
    @Test
    fun `sentences that open as a request to text name the person`() {
        mapOf(
            "Text Amira" to "Amira",
            "message Sam I'm running late" to "Sam",
            "Please text my assistant hello" to "assistant",
            "Send a text to Amira saying hi" to "Amira",
            "send Sam a message" to "Sam",
            "WhatsApp Amira hello" to "Amira",
        ).forEach { (said, who) -> assertThat(MessageRouting.addressee(said)).isEqualTo(who) }
    }

    @Test
    fun `screen text and other uses of the word are not a request to text`() {
        listOf(
            "read the text on my screen",
            "what does this text say",
            "text me when it is done",
            "message from Sam",
            "send me the report",
            "what is the text message on screen",
            "text on the screen",
        ).forEach { assertThat(MessageRouting.addressee(it)).isNull() }
    }
}
