/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The app has one look, dark, and does not follow the system theme or dynamic
 * colour. That is a deliberate departure from Material You: the palette encodes
 * meaning — red is live capture, amber is the system talking about itself, green is
 * a completed action — and letting the wallpaper recolour it would destroy that.
 * A surveillance readout that changes colour with the user's wallpaper is also just
 * the wrong idea.
 */
private val AssistantScheme = darkColorScheme(
    primary = AssistantColors.Bone,
    onPrimary = AssistantColors.Void,
    secondary = AssistantColors.Irrelevant,
    onSecondary = AssistantColors.Void,
    tertiary = AssistantColors.Admin,
    onTertiary = AssistantColors.Void,
    background = AssistantColors.Void,
    onBackground = AssistantColors.Bone,
    surface = AssistantColors.Panel,
    onSurface = AssistantColors.Bone,
    surfaceVariant = AssistantColors.PanelActive,
    onSurfaceVariant = AssistantColors.Dim,
    outline = AssistantColors.Rule,
    outlineVariant = AssistantColors.Rule,
    error = AssistantColors.Relevant,
    onError = AssistantColors.Bone,
    errorContainer = AssistantColors.Panel,
    onErrorContainer = AssistantColors.Relevant,
)

@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AssistantScheme,
        typography = AssistantTypography,
        content = content,
    )
}
