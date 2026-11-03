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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.ijazshare.assistant.ui.assistant.AssistantRule
import io.github.ijazshare.assistant.ui.assistant.scanlines
import io.github.ijazshare.assistant.ui.theme.AssistantColors
import io.github.ijazshare.assistant.ui.theme.AssistantLabel
import io.github.ijazshare.assistant.ui.theme.HaloTitle

/** Every secondary screen: the same top bar, the same ground, the same scanline wash. */
@Composable
fun ScreenFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AssistantColors.Void)
            .scanlines(),
    ) {
        ScreenTopBar(title, onBack)
        content(Modifier.weight(1f).fillMaxWidth())
    }
}

/** Back arrow top-left (48 dp, "Back") and a title naming where you are. */
@Composable
fun ScreenTopBar(title: String, onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(24.dp)) {
                    val stroke = 2.dp.toPx()
                    val tip = Offset(2.dp.toPx(), size.height / 2f)
                    val tail = Offset(size.width - 2.dp.toPx(), size.height / 2f)
                    val arm = 8.dp.toPx()
                    val colour = AssistantColors.Admin
                    drawLine(colour, tip, tail, stroke, StrokeCap.Round)
                    drawLine(colour, tip, Offset(tip.x + arm, tip.y - arm), stroke, StrokeCap.Round)
                    drawLine(colour, tip, Offset(tip.x + arm, tip.y + arm), stroke, StrokeCap.Round)
                }
            }
            Text(
                text = title,
                style = HaloTitle,
                color = AssistantColors.Bone,
                modifier = Modifier.padding(start = 8.dp).semantics { heading() },
            )
        }
        AssistantRule(Modifier.fillMaxWidth().height(1.dp))
    }
}

/** A tracked-caps text action with a 48 dp touch target. */
@Composable
fun ActionText(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = AssistantLabel,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = style, color = color)
    }
}

/** Marks a text as a section heading for screen readers. */
fun Modifier.heading(): Modifier = this.semantics { heading() }
