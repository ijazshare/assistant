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

/** What the words of a time request say about the day. */
internal object TimeWords {

    /**
     * Whether the transcript asks for a day past tomorrow ("the day after tomorrow"). The alarm and
     * reminder tools only have today and tomorrow, so the model cannot say it either way.
     */
    fun beyondTomorrow(transcript: String): Boolean =
        transcript.lowercase().split(NOT_LETTERS).zipWithNext().any { it == "after" to "tomorrow" }

    private val NOT_LETTERS = Regex("[^a-z]+")
}
