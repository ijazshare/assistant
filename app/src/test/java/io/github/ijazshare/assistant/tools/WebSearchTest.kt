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

class WebSearchTest {
    @Test
    fun `search phrases give the words to search for`() {
        assertThat(WebSearch.query("Search for pizza near me.")).isEqualTo("pizza near me")
        assertThat(WebSearch.query("search the web for train times to Leeds")).isEqualTo("train times to Leeds")
        assertThat(WebSearch.query("Google how tall is Everest?")).isEqualTo("how tall is Everest")
        assertThat(WebSearch.query("Can you look up the weather in Paris online")).isEqualTo("the weather in Paris")
        assertThat(WebSearch.query("search online, cheap flights")).isEqualTo("cheap flights")
    }

    @Test
    fun `anything else is not a web search`() {
        assertThat(WebSearch.query("What time is it?")).isNull()
        assertThat(WebSearch.query("Text Sam that I'll google it later")).isNull()
        assertThat(WebSearch.query("search for")).isNull()
        assertThat(WebSearch.query("Open Google")).isNull()
    }
}
