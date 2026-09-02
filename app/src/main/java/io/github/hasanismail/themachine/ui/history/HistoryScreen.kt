/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.hasanismail.themachine.assistant.CommandCache
import io.github.hasanismail.themachine.history.QueryLog
import io.github.hasanismail.themachine.history.QueryRecord
import io.github.hasanismail.themachine.history.QuerySource
import io.github.hasanismail.themachine.history.Resolution
import io.github.hasanismail.themachine.ui.theme.MachineColors
import io.github.hasanismail.themachine.ui.theme.MachineLabel
import io.github.hasanismail.themachine.ui.theme.MachineReadout
import io.github.hasanismail.themachine.ui.theme.MachineStatus
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every command the assistant has handled, newest first, and the phrases it has learned
 * to run without the model.
 *
 * Both are the user's own record and both can be wiped from here. Nothing on this
 * screen has ever left the phone.
 */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val log = remember { QueryLog(context) }
    val cache = remember {
        CommandCache.shared(File(context.getExternalFilesDir(null), CommandCache.FILE_NAME))
    }
    var records by remember { mutableStateOf(log.recent()) }
    var learned by remember { mutableStateOf(cache.size) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("HISTORY", style = MachineLabel, color = MachineColors.Admin)
        Text(
            text = "${records.size} COMMANDS · $learned LEARNED",
            style = MachineReadout,
            color = MachineColors.Dim,
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = "CLEAR HISTORY",
                style = MachineLabel,
                color = MachineColors.Relevant,
                modifier = Modifier.clickable {
                    log.clear()
                    records = emptyList()
                },
            )
            Text(
                text = "FORGET LEARNED",
                style = MachineLabel,
                color = MachineColors.Relevant,
                modifier = Modifier.clickable {
                    cache.clear()
                    learned = 0
                },
            )
        }
        Spacer(Modifier.height(16.dp))

        if (records.isEmpty()) {
            Text("NOTHING YET. HOLD THE SIDE BUTTON.", style = MachineReadout, color = MachineColors.Dim)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(records, key = { it.atEpochMillis }) { record -> RecordRow(record) }
        }
    }
}

@Composable
private fun RecordRow(record: QueryRecord) {
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = TIME.format(Instant.ofEpochMilli(record.atEpochMillis)),
                style = MachineReadout,
                color = MachineColors.Dim,
            )
            Text(
                text = when (record.resolution) {
                    Resolution.CACHE -> "INSTANT"
                    Resolution.MODEL -> "${record.llmMillis} MS"
                    Resolution.FAILED -> "FAILED"
                },
                style = MachineReadout,
                color = when (record.resolution) {
                    Resolution.CACHE -> MachineColors.Relevant
                    Resolution.MODEL -> MachineColors.Admin
                    Resolution.FAILED -> MachineColors.Dim
                },
            )
            if (record.source == QuerySource.TYPED) {
                Text("TYPED", style = MachineReadout, color = MachineColors.Dim)
            }
        }
        Text(record.transcript, style = MachineStatus, color = MachineColors.Bone)
        Text(
            text = buildString {
                record.tool?.let { append(it.uppercase()) }
                if (record.arguments.isNotEmpty()) {
                    append("  ")
                    append(record.arguments.entries.joinToString(" ") { "${it.key}=${it.value}" })
                }
            }.ifBlank { record.spoken },
            style = MachineReadout,
            color = if (record.success) MachineColors.Dim else MachineColors.Relevant,
        )
        if (record.tool != null) {
            Text(record.spoken, style = MachineReadout, color = MachineColors.Dim)
        }
    }
}

private val TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.systemDefault())
