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
 * A Halo-trilogy palette: deep UNSC navy grounds, Cortana-cyan holographic panels, and a
 * small set of HUD accents — shield green for what worked, Covenant orange for a warning.
 * Cool, faintly cyan whites, the way a MJOLNIR visor tints everything it reports.
 *
 * The names carry meaning so callers keep working: Admin is the primary cyan the app is
 * lit with, Relevant is a live/alert state, Asset is a completed action.
 *
 * Written from scratch. No third-party assets are used anywhere in this project —
 * see "Licence and attribution" in the README.
 */
object MachineColors {

    /** Ground. Deep UNSC navy, nearly black with a blue cast. */
    val Void = Color(0xFF050A12)

    /** Panel fill — a holographic pane lifted off the ground, cyan-tinted. */
    val Panel = Color(0xFF0B1826)

    /** Panel fill for something the system is actively working on. */
    val PanelActive = Color(0xFF11283C)

    /** Hairlines, HUD rails, inactive edges — dim cyan. */
    val Rule = Color(0xFF1F4A66)

    /** Primary readout text. A cool, faintly cyan white rather than pure white. */
    val Bone = Color(0xFFDCF2FF)

    /** Secondary readout: labels, units, timestamps. */
    val Dim = Color(0xFF83AAC6)

    /** Tertiary: hint text, disabled. */
    val Ghost = Color(0xFF4C6D84)

    // ---- HUD accents ------------------------------------------------------------

    /** Live capture, alerts, destructive actions — Covenant orange. */
    val Relevant = Color(0xFFFF7A45)

    /** The primary accent: Cortana cyan. Active state, links, the system speaking. */
    val Admin = Color(0xFF54D2FF)

    /** Understood input, completed work — shield green. */
    val Asset = Color(0xFF57E3A9)

    /** Passive information — a lighter holographic cyan. */
    val Irrelevant = Color(0xFF8FE1FF)

    // ---- Derived ----------------------------------------------------------------

    /** The hologram scanline wash, laid faintly over panels. Kept well below text contrast. */
    val Scanline = Color(0x0F63D4FF)

    /** Soft glows behind an active accent. */
    val RelevantGlow = Color(0x33FF7A45)
    val AdminGlow = Color(0x3354D2FF)
    val AssetGlow = Color(0x3357E3A9)
}
