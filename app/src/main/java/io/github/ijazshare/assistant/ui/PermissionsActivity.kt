/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ijazshare.assistant.ui.onboarding.AccessChecklist

/** Everything the assistant asks for, on its own page so the home screen stays clean. */
class PermissionsActivity : ScreenActivity() {
    override val title = "System access"
    override val content: @Composable (Modifier) -> Unit = {
        AccessChecklist(
            modifier = it
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
        )
    }
}
