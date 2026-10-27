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

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The device's own sans-serif family. Clean and modern, and using the system font
 * keeps the APK small and avoids a font licence to account for in the attribution
 * table. The old monospace terminal look is gone; a couple of numeric readouts still
 * ask for tabular figures where columns must line up.
 */
private val Sans = FontFamily.Default

/** Small tracked labels: section headers, units, captions. */
val AssistantLabel = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.4.sp,
)

/** Status lines and titles: Listening, Alarm set, the app name. */
val AssistantStatus = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.sp,
)

/** Body / numeric readouts — timings, sizes, percentages. */
val AssistantReadout = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 19.sp,
    letterSpacing = 0.1.sp,
)

/** Denser body, for longer diagnostic text. */
val AssistantDump = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
)

val AssistantTypography = Typography(
    displayLarge = AssistantStatus.copy(fontSize = 34.sp),
    headlineMedium = AssistantStatus.copy(fontSize = 26.sp),
    headlineSmall = AssistantStatus,
    titleMedium = AssistantLabel.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = AssistantLabel.copy(fontSize = 13.sp),
    bodyMedium = AssistantReadout.copy(fontSize = 15.sp),
    bodySmall = AssistantDump,
    labelSmall = AssistantLabel.copy(fontSize = 11.sp),
    labelMedium = AssistantLabel,
)
