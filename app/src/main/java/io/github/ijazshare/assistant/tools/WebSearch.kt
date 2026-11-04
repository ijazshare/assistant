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

/**
 * "Search for X", "google X", "look up X": read by code, never by the model, and handed to the
 * browser as a search page (decision 9: the browser goes online, Assistant sends nothing itself).
 */
object WebSearch {
    // ponytail: one fixed engine; a setting when the owner wants a different one.
    const val URL = "https://duckduckgo.com/?q="

    private val said = Regex(
        "^(?:(?:please|can you|could you|would you|hey|okay|ok)[ ,]+)*" +
            "(?:search (?:the web |online |the internet |google )?for|search (?:the web|online|the internet|google)" +
            "|google|look up)[ ,:]+(.+?)(?: (?:online|on the web|on the internet|on google))?$",
        RegexOption.IGNORE_CASE,
    )

    /** The words to search for, or null when the sentence is not a web search. */
    fun query(transcript: String): String? =
        said.matchEntire(transcript.trim().trimEnd('.', '?', '!', ' '))?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
}
