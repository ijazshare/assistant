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

import io.github.ijazshare.assistant.history.Resolution
import io.github.ijazshare.assistant.tools.ToolCall

internal data class PendingChoice(
    val transcript: String,
    val call: ToolCall,
    val sttMillis: Long,
    val llmMillis: Long,
    val resolution: Resolution,
)
