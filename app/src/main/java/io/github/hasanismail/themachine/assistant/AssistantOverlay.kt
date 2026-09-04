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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hasanismail.themachine.ui.machine.IndeterminateCells
import io.github.hasanismail.themachine.ui.machine.LevelMeter

// ponytail: the whole minimal skin lives in this one file, so the launcher/boot
// screens keep the terminal theme untouched. Widen scope only if the user asks.
private val OnCard = Color(0xFFECEFF3)
private val Muted = Color(0xFFA0AAB8)
private val Faint = Color(0xFF6A7684)
private val Accent = Color(0xFF8CB8FF)
private val Bad = Color(0xFFF2857F)

// Translucent glass: the assistant window is transparent behind the card, so a
// semi-opaque gradient genuinely shows the app underneath. Top is lighter and cooler,
// bottom darker, with a lit top rim to read as a raised pane of glass.
private val CardShape = RoundedCornerShape(30.dp)
private val CardBrush = Brush.verticalGradient(listOf(Color(0xEB222A3A), Color(0xD40C1017)))
private val RimBrush = Brush.verticalGradient(listOf(Color(0x3DFFFFFF), Color(0x0AFFFFFF)))

private val TitleStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp)
private val BodyStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 24.sp)
private val ResultStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 27.sp)
private val LabelStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.3.sp)
private val FootStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 11.sp, letterSpacing = 0.2.sp)

/**
 * What the side button brings up.
 *
 * Sits at the bottom over whatever the user was doing rather than taking the screen —
 * an assistant that hides the thing you are asking about is the wrong shape. Tapping
 * outside dismisses. A rounded card that slides up, in the shape people already know
 * from the system assistant, rather than a full-screen readout.
 */
@Composable
fun AssistantOverlay(showCount: Int, hideCount: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { VoiceSession(context, scope) }
    val state by session.state.collectAsStateWithLifecycle()

    // Each summon starts in voice mode with an empty draft; typing is a choice made per
    // summon, not a setting.
    var typing by remember(showCount) { mutableStateOf(false) }
    var draft by remember(showCount) { mutableStateOf("") }

    // Drives the slide-up: false on each fresh summon, flipped true a frame later so the
    // card animates in rather than appearing whole.
    var revealed by remember(showCount) { mutableStateOf(false) }

    // Each summon starts a fresh listen; the session object outlives the reveal so the
    // loaded Whisper context is not paid for again on a follow-up.
    LaunchedEffect(showCount) {
        if (showCount > 0) {
            revealed = true
            session.start()
        }
    }
    // Dismissing the overlay ends the command. Without this the microphone stayed open
    // behind whatever the user went back to, and an alarm they had changed their mind
    // about was still set and still spoken aloud.
    LaunchedEffect(hideCount) {
        if (hideCount > 0) session.stopListening()
    }
    DisposableEffect(Unit) {
        onDispose { session.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = revealed,
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Above the keyboard when there is one, and deaf to the tap that
                    // dismisses the session: a tap on the panel is aimed at the panel.
                    .imePadding()
                    .padding(12.dp)
                    .shadow(24.dp, CardShape, clip = false)
                    .clip(CardShape)
                    .background(CardBrush)
                    .border(1.dp, RimBrush, CardShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Assistant", style = TitleStyle, color = Muted)
                    Text(
                        text = if (typing) "Speak" else "Type",
                        style = LabelStyle,
                        color = Accent,
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
                }

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
        textStyle = BodyStyle.copy(color = OnCard),
        cursorBrush = SolidColor(Accent),
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
            Box {
                if (value.isEmpty()) {
                    Text("Type a command", style = BodyStyle, color = Faint)
                }
                inner()
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
            Working("Getting ready")
        }

        is SessionState.Listening -> {
            Text(
                text = if (state.heardSpeech) "Listening" else "Speak now",
                style = LabelStyle,
                color = Accent,
            )
            // The words so far, while they are still being said.
            if (state.partial.isNotBlank()) {
                Text(state.partial, style = BodyStyle, color = OnCard)
            }
            LevelMeter(
                level = state.level,
                modifier = Modifier.fillMaxWidth().height(24.dp),
                color = Accent,
            )
        }

        SessionState.Transcribing -> Working("Transcribing")

        is SessionState.Thinking -> {
            Text(state.transcript, style = BodyStyle, color = OnCard)
            Working("Thinking")
        }

        is SessionState.Done -> {
            Text(state.transcript, style = BodyStyle, color = Muted)
            Text(
                text = state.result.spoken,
                style = ResultStyle,
                color = if (state.result.success) OnCard else Bad,
            )
            state.result.detail?.let {
                Text(it, style = BodyStyle.copy(fontSize = 15.sp), color = Muted)
            }
            Text(
                text = footnote(state),
                style = FootStyle,
                color = Faint,
            )
        }

        is SessionState.Problem -> {
            Text("Couldn't do that", style = LabelStyle, color = Bad)
            Text(state.message, style = BodyStyle.copy(fontSize = 16.sp), color = OnCard)
            state.actionable?.let {
                Text(it, style = BodyStyle.copy(fontSize = 15.sp), color = Muted)
            }
        }
    }
}

/** A calm status line plus a quiet activity bar, for any state that is still working. */
@Composable
private fun Working(label: String) {
    Text(label, style = LabelStyle, color = Muted)
    IndeterminateCells(
        modifier = Modifier.fillMaxWidth().height(4.dp),
        color = Accent,
    )
}

/** The dim timing/tool footnote — kept because "minimal but still informative". */
private fun footnote(state: SessionState.Done): String {
    val how = if (state.fromCache) "instant" else "${state.timing.totalMillis} ms"
    return "${state.tool.replace('_', ' ')} · $how"
}
