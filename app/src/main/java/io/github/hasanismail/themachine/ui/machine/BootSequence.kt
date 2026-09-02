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
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hasanismail.themachine.audio.MachineSounds
import io.github.hasanismail.themachine.ui.theme.MachineColors
import io.github.hasanismail.themachine.ui.theme.MachineLabel
import io.github.hasanismail.themachine.ui.theme.MachineReadout
import kotlinx.coroutines.delay

/**
 * The cold-start readout: lines land one at a time with a tick, the way a system
 * reports each subsystem coming up.
 *
 * Deliberately fast. The point is to make the app feel like it is *checking* things,
 * not to make the user wait — the whole sequence is under a second, and the caller
 * decides what happens after [onComplete].
 */
@Composable
fun BootSequence(
    lines: List<BootLine>,
    modifier: Modifier = Modifier,
    lineDelayMillis: Long = 90,
    withSound: Boolean = true,
    onComplete: () -> Unit = {},
) {
    var revealed by remember(lines) { mutableIntStateOf(0) }

    LaunchedEffect(lines) {
        if (withSound) MachineSounds.play(MachineSounds.Cue.BOOT, volume = 0.35f)
        for (i in lines.indices) {
            delay(lineDelayMillis)
            revealed = i + 1
            if (withSound) MachineSounds.play(MachineSounds.Cue.TICK, volume = 0.25f)
        }
        onComplete()
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 0 until revealed) {
            BootRow(lines[i])
        }
        if (revealed < lines.size) {
            Caret()
        }
    }
}

@Composable
private fun BootRow(line: BootLine) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = line.label,
            style = MachineLabel,
            color = MachineColors.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = line.value,
            style = MachineReadout,
            color = line.tone,
            maxLines = 1,
        )
    }
}

/** A blinking block cursor shown while the readout is still filling in. */
@Composable
private fun Caret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "caretAlpha",
    )
    Text(
        text = "█",
        style = MachineReadout,
        color = MachineColors.Bone.copy(alpha = alpha),
        modifier = Modifier.width(12.dp),
    )
}
