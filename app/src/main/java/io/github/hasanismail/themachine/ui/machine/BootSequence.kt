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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hasanismail.themachine.ui.theme.MachineColors
import io.github.hasanismail.themachine.ui.theme.MachineLabel
import io.github.hasanismail.themachine.ui.theme.MachineReadout

/**
 * A small status readout: a label on the left, a value on the right, one row per line.
 *
 * This used to type itself out a line at a time with ticks, like a booting terminal.
 * The modern look shows it settled and calm; [onComplete] still fires so callers that
 * gated an animation on it keep working.
 */
@Composable
fun BootSequence(
    lines: List<BootLine>,
    modifier: Modifier = Modifier,
    onComplete: () -> Unit = {},
) {
    LaunchedEffect(lines) { onComplete() }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (line in lines) {
            BootRow(line)
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
