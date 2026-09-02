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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hasanismail.themachine.audio.MachineSounds
import io.github.hasanismail.themachine.ui.machine.IndeterminateCells
import io.github.hasanismail.themachine.ui.machine.LevelMeter
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
 * Sits at the bottom over whatever the user was doing rather than taking the screen —
 * an assistant that hides the thing you are asking about is the wrong shape. Tapping
 * outside dismisses.
 */
@Composable
fun AssistantOverlay(showCount: Int, hidden: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { VoiceSession(context, scope) }
    val state by session.state.collectAsStateWithLifecycle()

    // Each summon starts in voice mode with an empty draft; typing is a choice made per
    // summon, not a setting.
    var typing by remember(showCount) { mutableStateOf(false) }
    var draft by remember(showCount) { mutableStateOf("") }

    // Each summon starts a fresh listen; the session object outlives the reveal so the
    // loaded Whisper context is not paid for again on a follow-up.
    LaunchedEffect(showCount) {
        if (showCount > 0) session.start()
    }
    // Dismissing the overlay ends the command. Without this the microphone stayed open
    // behind whatever the user went back to, and an alarm they had changed their mind
    // about was still set and still spoken aloud.
    LaunchedEffect(hidden) {
        if (hidden) session.stopListening()
    }
    DisposableEffect(Unit) {
        onDispose { session.release() }
    }

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
                    // Above the keyboard when there is one, and deaf to the tap that
                    // dismisses the session: a tap on the panel is aimed at the panel.
                    .imePadding()
                    .padding(12.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .background(MachineColors.Void)
                    .scanlines(),
                color = state.tone(),
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
                        Text("THE MACHINE", style = MachineLabel, color = state.tone())
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = if (typing) "SPEAK" else "TYPE",
                                style = MachineLabel,
                                color = MachineColors.Relevant,
                                modifier = Modifier.clickable {
                                    if (typing) {
                                        typing = false
                                        session.start()
                                    } else {
                                        session.stopListening()
                                        typing = true
                                    }
                                },
                            )
                            Text("TAP TO DISMISS", style = MachineLabel, color = MachineColors.Ghost)
                        }
                    }
                    MachineRule(Modifier.fillMaxWidth().height(1.dp))

                    Greeting(showCount)
                    if (typing) {
                        CommandField(
                            value = draft,
                            onValueChange = { draft = it },
                            onSend = {
                                val text = draft
                                typing = false
                                session.submitText(text)
                            },
                        )
                    }
                    SessionBody(state)
                }
            }
        }
    }
}

/**
 * A single line to type a command into, focused and with the keyboard up as soon as it
 * appears. Send runs the command through exactly the path speech takes, minus the
 * transcription.
 */
@Composable
private fun CommandField(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MachineStatus.copy(color = MachineColors.Bone),
        cursorBrush = SolidColor(MachineColors.Relevant),
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Send,
            capitalization = KeyboardCapitalization.Sentences,
        ),
        keyboardActions = KeyboardActions(
            onSend = {
                keyboard?.hide()
                onSend()
            },
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        decorationBox = { inner ->
            Column {
                Text("TYPE A COMMAND", style = MachineLabel, color = MachineColors.Admin)
                Box(Modifier.padding(vertical = 6.dp)) {
                    if (value.isEmpty()) {
                        Text("timer ten minutes", style = MachineStatus, color = MachineColors.Ghost)
                    }
                    inner()
                }
                MachineRule(Modifier.fillMaxWidth().height(1.dp))
            }
        },
    )
    // Focus once the field exists and the window already holds focus; asking in the same
    // frame the field appears can be dropped.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

@Composable
private fun SessionBody(state: SessionState) {
    when (state) {
        SessionState.Idle, SessionState.Preparing -> {
            Text("STANDBY", style = MachineLabel, color = MachineColors.Dim)
            IndeterminateCells(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MachineColors.Admin,
            )
        }

        is SessionState.Listening -> {
            Text(
                text = if (state.heardSpeech) "LISTENING" else "SPEAK NOW",
                style = MachineLabel,
                color = MachineColors.Relevant,
            )
            // The words so far, while they are still being said. Held in place once it
            // has any height, so the meter below does not jump on every partial.
            if (state.partial.isNotBlank()) {
                Text(
                    text = state.partial,
                    style = MachineStatus,
                    color = MachineColors.Bone,
                )
            }
            LevelMeter(
                level = state.level,
                modifier = Modifier.fillMaxWidth().height(28.dp),
                color = MachineColors.Relevant,
            )
        }

        SessionState.Transcribing -> {
            Text("TRANSCRIBING", style = MachineLabel, color = MachineColors.Admin)
            IndeterminateCells(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MachineColors.Admin,
            )
        }

        is SessionState.Thinking -> {
            Text(text = state.transcript, style = MachineStatus, color = MachineColors.Bone)
            Text("UNDERSTANDING", style = MachineLabel, color = MachineColors.Admin)
            IndeterminateCells(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MachineColors.Admin,
            )
        }

        is SessionState.Done -> {
            Text(
                text = if (state.fromCache) "${state.transcript}  ·  INSTANT" else state.transcript,
                style = MachineReadout,
                color = if (state.fromCache) MachineColors.Relevant else MachineColors.Dim,
            )
            Text(
                text = state.result.spoken,
                style = MachineStatus,
                color = if (state.result.success) MachineColors.Bone else MachineColors.Relevant,
            )
            state.result.detail?.let {
                Text(it, style = MachineReadout, color = MachineColors.Dim)
            }
            Text(
                text = state.tool.uppercase() + "   " +
                    "${state.timing.sttMillis} + ${state.timing.llmMillis} MS  =  " +
                    "${state.timing.totalMillis} MS",
                style = MachineLabel,
                color = if (state.result.success) MachineColors.Asset else MachineColors.Relevant,
            )
        }

        is SessionState.Problem -> {
            Text("UNABLE", style = MachineLabel, color = MachineColors.Relevant)
            Text(state.message, style = MachineReadout, color = MachineColors.Bone)
            state.actionable?.let {
                Text(it, style = MachineReadout, color = MachineColors.Dim)
            }
        }
    }
}

private fun SessionState.tone() = when (this) {
    is SessionState.Problem -> MachineColors.Relevant
    is SessionState.Done -> if (result.success) MachineColors.Asset else MachineColors.Relevant
    is SessionState.Listening -> MachineColors.Relevant
    else -> MachineColors.Admin
}

/**
 * The greeting, delivered a word at a time.
 *
 * The full stops between the words are the point — a system assembling a sentence out
 * of pieces, not a phrase read aloud. Showing it all at once throws that away.
 */
@Composable
private fun Greeting(showCount: Int) {
    val words = remember { listOf("Can.", "You.", "Hear", "Me.?") }
    var shown by remember(showCount) { mutableIntStateOf(0) }

    LaunchedEffect(showCount) {
        // A beat before the first word: the pause is what makes it land.
        delay(GREETING_LEAD_IN_MS)
        for (i in words.indices) {
            shown = i + 1
            MachineSounds.play(MachineSounds.Cue.TICK, volume = 0.3f)
            delay(GREETING_WORD_GAP_MS)
        }
    }

    Text(
        text = words.take(shown).joinToString(" "),
        style = MachineStatus,
        color = MachineColors.Bone,
        // Reserve the height up front so the panel does not grow line by line.
        minLines = 2,
    )
}

private const val GREETING_LEAD_IN_MS = 220L
private const val GREETING_WORD_GAP_MS = 340L
