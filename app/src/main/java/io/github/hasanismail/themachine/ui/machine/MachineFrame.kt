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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hasanismail.themachine.ui.theme.MachineColors

/**
 * The tracking box: four corner brackets that snap inward onto whatever they are
 * framing, rather than a closed rectangle.
 *
 * [progress] drives the snap — 0 means the brackets sit spread out and faint,
 * 1 means locked on. Callers usually animate it via [rememberSnapProgress].
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
    Box(
        modifier = modifier
            .drawBehind {
                if (filled) {
                    drawRect(MachineColors.Panel)
                }
                drawTrackingCorners(
                    color = color,
                    progress = progress,
                    cornerLengthPx = cornerLength.toPx(),
                    strokePx = strokeWidth.toPx(),
                )
            }
            .padding(strokeWidth + 4.dp),
        content = content,
    )
}

/** How far outside the box the brackets start, as a fraction of the shortest side. */
private const val BRACKET_SPREAD_FRACTION = 0.22f

/** Bracket opacity when fully spread, and how much it gains as it locks on. */
private const val BRACKET_ALPHA_FLOOR = 0.25f
private const val BRACKET_ALPHA_RANGE = 0.75f

/** Arm length when fully spread, and how much it grows as it locks on. */
private const val BRACKET_ARM_FLOOR = 0.6f
private const val BRACKET_ARM_RANGE = 0.4f

/**
 * Corners are drawn as six line segments each — two arms and a short tick — offset
 * outward by an amount that shrinks to zero as [progress] reaches 1. The overshoot is
 * what makes it read as *snapping* rather than fading in.
 */
private fun DrawScope.drawTrackingCorners(
    color: Color,
    progress: Float,
    cornerLengthPx: Float,
    strokePx: Float,
) {
    val p = progress.coerceIn(0f, 1f)
    // Spread starts at a quarter of the shortest side and closes to nothing.
    val spread = (1f - p) * (minOf(size.width, size.height) * BRACKET_SPREAD_FRACTION)
    val alpha = BRACKET_ALPHA_FLOOR + BRACKET_ALPHA_RANGE * p
    val arm = cornerLengthPx * (BRACKET_ARM_FLOOR + BRACKET_ARM_RANGE * p)

    val left = -spread
    val top = -spread
    val right = size.width + spread
    val bottom = size.height + spread

    fun seg(x1: Float, y1: Float, x2: Float, y2: Float) {
        drawLine(
            color = color.copy(alpha = color.alpha * alpha),
            start = Offset(x1, y1),
            end = Offset(x2, y2),
            strokeWidth = strokePx,
        )
    }

    // Top-left
    seg(left, top, left + arm, top)
    seg(left, top, left, top + arm)
    // Top-right
    seg(right, top, right - arm, top)
    seg(right, top, right, top + arm)
    // Bottom-left
    seg(left, bottom, left + arm, bottom)
    seg(left, bottom, left, bottom - arm)
    // Bottom-right
    seg(right, bottom, right - arm, bottom)
    seg(right, bottom, right, bottom - arm)
}

/**
 * Animates a lock-on. Snappy and linear on purpose — the Machine is mechanical, so
 * no spring overshoot or decelerate easing.
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

/**
 * The static scanline grille laid over a frame.
 *
 * A modifier rather than a stacked composable so it costs no extra layout node, and
 * kept far below text contrast so it never hurts legibility. The travelling bar is
 * [ScanSweep], which needs an infinite transition and so has to be a composable.
 */
fun Modifier.scanlines(spacingDp: Float = 3f): Modifier = this then Modifier.drawWithContent {
    drawContent()
    val spacing = spacingDp * density
    var y = 0f
    while (y < size.height) {
        drawRect(
            color = MachineColors.Scanline,
            topLeft = Offset(0f, y),
            size = Size(size.width, 1f),
        )
        y += spacing
    }
}

/**
 * The travelling scan bar. Separated from [scanlines] because it needs an infinite
 * transition and therefore has to be a composable, not a plain modifier.
 */
@Composable
fun ScanSweep(
    modifier: Modifier = Modifier,
    color: Color = MachineColors.Irrelevant,
    periodMillis: Int = 2600,
) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val position by transition.animateFloat(
        initialValue = -0.1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweepPos",
    )
    Box(
        modifier = modifier.drawBehind {
            val y = size.height * position
            val bandHeight = 56f
            // A soft leading edge and a hard trailing line, so it reads directionally.
            for (i in 0 until 8) {
                val t = i / 8f
                drawRect(
                    color = color.copy(alpha = 0.05f * (1f - t)),
                    topLeft = Offset(0f, y - bandHeight * t),
                    size = Size(size.width, bandHeight / 8f),
                )
            }
            drawLine(
                color = color.copy(alpha = 0.35f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        },
    )
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
            val gap = 2f * density
            val cellWidth = (size.width - gap * (cells - 1)) / cells
            for (i in 0 until cells) {
                // Distance behind the head, wrapped, so the lit window is contiguous.
                val distance = (head.toInt() - i + cells) % cells
                val intensity = if (distance < lit) 1f - distance / lit.toFloat() else 0f
                drawRect(
                    color = if (intensity > 0f) {
                        color.copy(alpha = 0.15f + 0.85f * intensity)
                    } else {
                        MachineColors.Rule
                    },
                    topLeft = Offset(i * (cellWidth + gap), 0f),
                    size = Size(cellWidth, size.height),
                )
            }
        },
    )
}

/** Boxed caption stamped on a frame, e.g. "ADMIN" or "LISTENING". */
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
