/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hasanismail.themachine.audio.MachineSounds
import io.github.hasanismail.themachine.ui.machine.IndeterminateCells
import io.github.hasanismail.themachine.ui.machine.MachineRule
import io.github.hasanismail.themachine.ui.machine.TrackingBox
import io.github.hasanismail.themachine.ui.machine.rememberSnapProgress
import io.github.hasanismail.themachine.ui.machine.scanlines
import io.github.hasanismail.themachine.ui.theme.MachineColors
import io.github.hasanismail.themachine.ui.theme.MachineLabel
import io.github.hasanismail.themachine.ui.theme.MachineReadout
import io.github.hasanismail.themachine.ui.theme.MachineStatus
import io.github.hasanismail.themachine.ui.theme.TheMachineTheme
import kotlinx.coroutines.delay

/**
 * What the side button brings up.
 *
 * Sits at the bottom of the screen over whatever the user was doing, the way an
 * assistant should — it is not a full-screen takeover. Tapping outside dismisses.
 *
 * The capture and parsing pipeline lands in P3–P7; until then this states plainly what
 * is and is not wired rather than miming a listening animation that does nothing.
 */
@Composable
fun AssistantOverlay(showCount: Int, onDismiss: () -> Unit) {
    TheMachineTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            TrackingBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .background(MachineColors.Void)
                    .scanlines(),
                color = MachineColors.Relevant,
                progress = rememberSnapProgress(locked = true, durationMillis = 200),
                cornerLength = 22.dp,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("THE MACHINE", style = MachineLabel, color = MachineColors.Relevant)
                        Text("TAP TO DISMISS", style = MachineLabel, color = MachineColors.Ghost)
                    }
                    MachineRule(Modifier.fillMaxWidth().height(1.dp))

                    Greeting(showCount)

                    IndeterminateCells(
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MachineColors.Relevant,
                    )

                    Text(
                        text = "The side button now reaches The Machine. Speech capture, " +
                            "transcription and command parsing are the next phases — until they " +
                            "land this overlay has nothing to listen with.",
                        style = MachineReadout,
                        color = MachineColors.Dim,
                    )
                }
            }
        }
    }
}

/**
 * The greeting, delivered a word at a time.
 *
 * The full stops between the words are the point — it is a system assembling a sentence
 * out of pieces, not a phrase being read aloud. Showing it all at once would throw that
 * away, so each word lands on its own with a tick.
 */
@Composable
private fun Greeting(showCount: Int) {
    val words = remember { listOf("Can.", "You.", "Hear", "Me.?") }
    var shown by remember(showCount) { mutableIntStateOf(0) }

    LaunchedEffect(showCount) {
        // A beat before the first word: the pause is what makes it land.
        delay(220)
        for (i in words.indices) {
            shown = i + 1
            MachineSounds.play(MachineSounds.Cue.TICK, volume = 0.3f)
            delay(340)
        }
    }

    Text(
        text = words.take(shown).joinToString(" "),
        style = MachineStatus,
        color = MachineColors.Bone,
        // Reserve the full height up front so the panel does not grow line by line.
        minLines = 2,
    )
}
