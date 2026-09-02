/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The Machine has one look, dark, and does not follow the system theme or dynamic
 * colour. That is a deliberate departure from Material You: the palette encodes
 * meaning — red is live capture, amber is the system talking about itself, green is
 * a completed action — and letting the wallpaper recolour it would destroy that.
 * A surveillance readout that changes colour with the user's wallpaper is also just
 * the wrong idea.
 */
private val MachineScheme = darkColorScheme(
    primary = MachineColors.Bone,
    onPrimary = MachineColors.Void,
    secondary = MachineColors.Irrelevant,
    onSecondary = MachineColors.Void,
    tertiary = MachineColors.Admin,
    onTertiary = MachineColors.Void,
    background = MachineColors.Void,
    onBackground = MachineColors.Bone,
    surface = MachineColors.Panel,
    onSurface = MachineColors.Bone,
    surfaceVariant = MachineColors.PanelActive,
    onSurfaceVariant = MachineColors.Dim,
    outline = MachineColors.Rule,
    outlineVariant = MachineColors.Rule,
    error = MachineColors.Relevant,
    onError = MachineColors.Bone,
    errorContainer = MachineColors.Panel,
    onErrorContainer = MachineColors.Relevant,
)

@Composable
fun TheMachineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MachineScheme,
        typography = MachineTypography,
        content = content,
    )
}
