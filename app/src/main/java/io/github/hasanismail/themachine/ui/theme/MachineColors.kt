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

import androidx.compose.ui.graphics.Color

/**
 * A modern dark palette: deep slate ground, glass panels lifted a step above it, near-
 * white text, and a small set of soft accents. The names carry meaning — Relevant is
 * live/alert, Admin is the primary blue the app is accented with, Asset is a completed
 * action — so callers keep working while the values move to the glass aesthetic.
 *
 * Written from scratch. No third-party assets are used anywhere in this project —
 * see "Licence and attribution" in the README.
 */
object MachineColors {

    /** Ground. Deep cool slate rather than pure black. */
    val Void = Color(0xFF0C0F15)

    /** Panel fill, a step up from the ground. */
    val Panel = Color(0xFF161B24)

    /** Panel fill for something the system is actively working on. */
    val PanelActive = Color(0xFF1E2430)

    /** Hairlines, dividers, inactive rails. */
    val Rule = Color(0xFF2A3140)

    /** Primary readout text. A touch off pure white so it does not glare at night. */
    val Bone = Color(0xFFECEFF3)

    /** Secondary readout: labels, units, timestamps. */
    val Dim = Color(0xFFA0AAB8)

    /** Tertiary: hint text, disabled. */
    val Ghost = Color(0xFF6A7684)

    // ---- Accents ----------------------------------------------------------------
    // One colour, one meaning. Softened for the glass look but still legible.

    /** Live capture, alerts, destructive actions. */
    val Relevant = Color(0xFFF2857F)

    /** The primary accent: the system talking about itself, links, active state. */
    val Admin = Color(0xFF8CB8FF)

    /** Understood input, completed work, confirmations. */
    val Asset = Color(0xFF67D2A0)

    /** Passive information. */
    val Irrelevant = Color(0xFF6FD0E0)

    // ---- Derived ----------------------------------------------------------------

    /** Was the scanline wash; now transparent so the old grille disappears everywhere. */
    val Scanline = Color(0x00000000)

    /** Soft glows behind an active accent. */
    val RelevantGlow = Color(0x33F2857F)
    val AdminGlow = Color(0x338CB8FF)
    val AssetGlow = Color(0x3367D2A0)
}
