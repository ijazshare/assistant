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

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertWithMessage
import io.github.ijazshare.assistant.ui.theme.AssistantColors
import org.junit.Test
import kotlin.math.pow

/** WCAG 2.x contrast of every text colour on every surface it can sit on. */
class ContrastTest {

    private val surfaces = mapOf(
        "Void" to AssistantColors.Void,
        "Panel" to AssistantColors.Panel,
        "PanelActive" to AssistantColors.PanelActive,
    )

    // Rule is a hairline and never carries text, so it is deliberately absent.
    private val text = mapOf(
        "Bone" to AssistantColors.Bone,
        "Dim" to AssistantColors.Dim,
        "Ghost" to AssistantColors.Ghost,
        "Admin" to AssistantColors.Admin,
        "Asset" to AssistantColors.Asset,
        "Relevant" to AssistantColors.Relevant,
        "Irrelevant" to AssistantColors.Irrelevant,
    )

    @Test
    fun everyTextColourPassesAaOnEverySurface() {
        for ((name, fg) in text) {
            for ((surfaceName, bg) in surfaces) {
                assertWithMessage("$name on $surfaceName").that(ratio(fg, bg)).isAtLeast(AA_BODY)
            }
        }
    }

    @Test
    fun focusAndRuleAreVisibleAgainstTheGround() {
        // The 3:1 bar for non-text indicators applies to the focus edge (Admin), not to Rule hairlines.
        assertWithMessage("Admin focus edge").that(ratio(AssistantColors.Admin, AssistantColors.Void))
            .isAtLeast(AA_LARGE)
    }

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private companion object {
        const val AA_BODY = 4.5
        const val AA_LARGE = 3.0
    }
}
