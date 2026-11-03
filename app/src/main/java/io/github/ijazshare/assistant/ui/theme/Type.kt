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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.ijazshare.assistant.R

/**
 * The device's own sans-serif family. Clean and modern, and using the system font
 * keeps the APK small and avoids a font licence to account for in the attribution
 * table. The old monospace terminal look is gone; a couple of numeric readouts still
 * ask for tabular figures where columns must line up.
 */
private val Sans = FontFamily.Default

/**
 * Saira (SIL OFL 1.1), a squared HUD face, for titles, row labels and big numerals only.
 * One variable file serves both weights. It has `tnum`, so numerals keep their width.
 * Body text stays on [Sans]. Not used by the voice overlay, whose first frame must not
 * wait on a font.
 */
private val Saira = FontFamily(
    Font(
        R.font.saira,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.saira,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
)

/** Tracked caps row labels on Home and the section labels of the secondary screens. */
val HaloLabel = TextStyle(
    fontFamily = Saira,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.8.sp,
    fontFeatureSettings = "tnum",
)

/** Screen titles and the app name. */
val HaloTitle = TextStyle(
    fontFamily = Saira,
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.4.sp,
    fontFeatureSettings = "tnum",
)

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
