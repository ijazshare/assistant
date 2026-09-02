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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors =
    darkColorScheme(
        primary = MachinePurple80,
        secondary = MachinePurpleGrey80,
        tertiary = MachineTeal80,
    )

private val LightColors =
    lightColorScheme(
        primary = MachinePurple40,
        secondary = MachinePurpleGrey40,
        tertiary = MachineTeal40,
    )

@Composable
fun TheMachineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        when {
            dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
            dynamicColor -> dynamicLightColorScheme(context)
            darkTheme -> DarkColors
            else -> LightColors
        }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MachineTypography,
        content = content,
    )
}
