/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.ui.machine

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hasanismail.themachine.ui.theme.MachineColors

/** Glass panel fill and lit rim, shared by every card on every screen. */
private val CardBrush = Brush.verticalGradient(
    listOf(MachineColors.PanelActive, MachineColors.Panel),
)
private val RimBrush = Brush.verticalGradient(
    listOf(Color(0x1FFFFFFF), Color(0x0AFFFFFF)),
)

/**
 * A rounded glass card. This was the terminal "tracking box" of corner brackets; every
 * screen framed its rows with it, so redrawing it here as a soft raised panel re-skins
 * the whole app at once. The [color] and [progress] arguments are kept so existing
 * callers compile unchanged; a card does not need them.
 */
@Composable
fun TrackingBox(
    modifier: Modifier = Modifier,
    color: Color = MachineColors.Irrelevant,
    progress: Float = 1f,
    cornerLength: Dp = 14.dp,
    strokeWidth: Dp = 1.5.dp,
    filled: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(CardBrush)
            .border(1.dp, RimBrush, shape),
        content = content,
    )
}

/**
 * Kept so callers that animate a lock-on still compile. The card no longer draws the
 * snap, so this is just a settled value; harmless and cheap.
 */
@Composable
fun rememberSnapProgress(locked: Boolean, durationMillis: Int = 180): Float {
    val progress by animateFloatAsState(
        targetValue = if (locked) 1f else 0f,
        animationSpec = tween(durationMillis, easing = LinearEasing),
        label = "snap",
    )
    return progress
}

/** The old scanline grille, now off. Kept as a no-op so callers need no changes. */
fun Modifier.scanlines(spacingDp: Float = 3f): Modifier = this

/** The old travelling scan bar, now off. Kept as a no-op composable. */
@Composable
fun ScanSweep(
    modifier: Modifier = Modifier,
    color: Color = MachineColors.Irrelevant,
    periodMillis: Int = 2600,
) {
    Box(modifier)
}

/** Thin rule used to separate readout blocks. */
@Composable
fun MachineRule(
    modifier: Modifier = Modifier,
    color: Color = MachineColors.Rule,
) {
    Box(modifier.background(color))
}

/**
 * A ticking activity bar: a run of cells where a few are lit and the lit window
 * marches along. Used while something is working but has no measurable progress.
 */
@Composable
fun IndeterminateCells(
    modifier: Modifier = Modifier,
    cells: Int = 24,
    lit: Int = 5,
    color: Color = MachineColors.Admin,
    periodMillis: Int = 1100,
) {
    val transition = rememberInfiniteTransition(label = "cells")
    val head by transition.animateFloat(
        initialValue = 0f,
        targetValue = cells.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(periodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "cellHead",
    )
    Box(
        modifier = modifier.drawBehind {
            val gap = 3f * density
            val cellWidth = (size.width - gap * (cells - 1)) / cells
            for (i in 0 until cells) {
                // Distance behind the head, wrapped, so the lit window is contiguous.
                val distance = (head.toInt() - i + cells) % cells
                val intensity = if (distance < lit) 1f - distance / lit.toFloat() else 0f
                drawRoundRect(
                    color = if (intensity > 0f) {
                        color.copy(alpha = 0.2f + 0.8f * intensity)
                    } else {
                        MachineColors.Rule
                    },
                    topLeft = Offset(i * (cellWidth + gap), 0f),
                    size = Size(cellWidth, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                )
            }
        },
    )
}

/** A subtle outlined chip, e.g. a caption stamped on a card. */
@Composable
fun StampBorder(
    modifier: Modifier = Modifier,
    color: Color = MachineColors.Admin,
    strokeWidth: Dp = 1.dp,
) {
    Box(
        modifier.drawBehind {
            drawRect(
                color = color,
                style = Stroke(width = strokeWidth.toPx()),
            )
        },
    )
}

/**
 * A live input level, drawn as a mirrored bar of cells.
 *
 * A bar rather than a scrolling waveform: the useful question while speaking is "is it
 * hearing me", which a level answers instantly.
 */
@Composable
fun LevelMeter(
    level: Float,
    modifier: Modifier = Modifier,
    color: Color = MachineColors.Relevant,
    cells: Int = 32,
) {
    val smoothed by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(90, easing = LinearEasing),
        label = "level",
    )
    Box(
        modifier = modifier.drawBehind {
            val gap = 3f * density
            val cellWidth = (size.width - gap * (cells - 1)) / cells
            val lit = (smoothed * cells).toInt()
            for (i in 0 until cells) {
                // Grow from the middle out, so quiet speech still reads as centred.
                val distanceFromCentre = kotlin.math.abs(i - cells / 2) * 2
                val on = distanceFromCentre <= lit
                val height = if (on) {
                    size.height * (0.25f + 0.75f * (1f - distanceFromCentre / cells.toFloat()))
                } else {
                    size.height * 0.12f
                }
                drawRoundRect(
                    color = if (on) color else MachineColors.Rule,
                    topLeft = Offset(i * (cellWidth + gap), (size.height - height) / 2f),
                    size = Size(cellWidth, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cellWidth / 2f),
                )
            }
        },
    )
}
