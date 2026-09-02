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
 * A surveillance-terminal palette: near-black ground, bone-white text, and a small
 * set of saturated classification colours used sparingly enough that each one still
 * means something when it appears.
 *
 * Written from scratch. No third-party assets are used anywhere in this project —
 * see "Licence and attribution" in the README.
 */
object MachineColors {

    /** Ground. Very slightly blue rather than pure black, so OLED edges read as deliberate. */
    val Void = Color(0xFF05070A)

    /** Panel fill, one step up from the ground. */
    val Panel = Color(0xFF0B1016)

    /** Panel fill for something the system is actively working on. */
    val PanelActive = Color(0xFF121A24)

    /** Hairlines, inactive brackets, grid. */
    val Rule = Color(0xFF1E2A38)

    /** Primary readout text. Bone rather than pure white — pure white glares at night. */
    val Bone = Color(0xFFE6ECF2)

    /** Secondary readout: labels, units, timestamps. */
    val Dim = Color(0xFF7C8B9C)

    /** Tertiary: hint text, disabled. */
    val Ghost = Color(0xFF44525F)

    // ---- Classification accents -------------------------------------------------
    // One colour, one meaning. Used on brackets, status text and state glyphs.

    /** Live capture, threats, destructive actions. */
    val Relevant = Color(0xFFE01B24)

    /** The system talking about itself: admin state, boot, model management. */
    val Admin = Color(0xFFFFB000)

    /** Understood input, completed work, confirmations. */
    val Asset = Color(0xFF3DDC97)

    /** Passive tracking, informational overlays. */
    val Irrelevant = Color(0xFF4FA3D1)

    // ---- Derived ----------------------------------------------------------------

    /** Scanline wash laid over the whole frame. Kept far below the text contrast. */
    val Scanline = Color(0x0DFFFFFF)

    /** Glow behind an active bracket. */
    val RelevantGlow = Color(0x33E01B24)
    val AdminGlow = Color(0x33FFB000)
    val AssetGlow = Color(0x333DDC97)
}
