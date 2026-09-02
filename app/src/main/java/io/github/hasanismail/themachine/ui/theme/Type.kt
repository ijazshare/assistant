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

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.sp

/**
 * Everything is monospace. The system reads as machine output rather than prose, and
 * fixed advance widths mean a changing readout does not reflow the layout around it —
 * which matters when timings tick during a live session.
 *
 * The device's own monospace family is used rather than a bundled font: it keeps the
 * APK smaller and avoids a font licence to account for in the attribution table.
 */
private val Mono = FontFamily.Monospace

/** Wide-tracked all-caps, for labels the system stamps on things. */
val MachineLabel = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 2.4.sp,
)

/** Status lines: LISTENING, PROCESSING, ALARM SET. */
val MachineStatus = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = 3.sp,
)

/** Dense numeric readouts — timings, sizes, percentages. */
val MachineReadout = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 17.sp,
    letterSpacing = 0.5.sp,
)

/** Slightly condensed, for long diagnostic dumps that must not wrap awkwardly. */
val MachineDump = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.sp,
    textGeometricTransform = TextGeometricTransform(scaleX = 0.94f),
)

val MachineTypography = Typography(
    displayLarge = MachineStatus.copy(fontSize = 34.sp, letterSpacing = 4.sp),
    headlineMedium = MachineStatus.copy(fontSize = 24.sp),
    headlineSmall = MachineStatus,
    titleMedium = MachineLabel.copy(fontSize = 13.sp, letterSpacing = 1.8.sp),
    titleSmall = MachineLabel,
    bodyMedium = MachineReadout.copy(fontSize = 13.sp),
    bodySmall = MachineDump,
    labelSmall = MachineLabel.copy(fontSize = 10.sp),
    labelMedium = MachineLabel,
)
