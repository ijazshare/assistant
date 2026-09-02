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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * Both are the user's own record. A single learned phrase can be forgotten by tapping it,
 * which matters because a phrase learned wrongly is answered wrongly and instantly for as
 * long as it is remembered. Nothing on this screen has ever left the phone.
 */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val log = remember { QueryLog(context) }
    val cache = remember {
        CommandCache.shared(File(context.getExternalFilesDir(null), CommandCache.FILE_NAME))
    }
    var records by remember { mutableStateOf(log.recent()) }
    var learned by remember { mutableStateOf(cache.all()) }

    // One scrolling list, header included: as a Column above a LazyColumn the header sat
    // outside the scroll and the first record was clipped under it.
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text("HISTORY", style = MachineLabel, color = MachineColors.Admin)
                Text(
                    text = "${records.size} COMMANDS, ${learned.size} LEARNED",
                    style = MachineReadout,
                    color = MachineColors.Dim,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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
                        text = "FORGET ALL",
                        style = MachineLabel,
                        color = MachineColors.Relevant,
                        modifier = Modifier.clickable {
                            cache.clear()
                            learned = cache.all()
                        },
                    )
                }
            }
        }

        if (learned.isNotEmpty()) {
            item { Text("LEARNED PHRASES", style = MachineLabel, color = MachineColors.Admin) }
            items(learned, key = { "learned:" + it.key }) { entry ->
                LearnedRow(entry) {
                    cache.forgetKey(entry.key)
                    learned = cache.all()
                }
            }
            item { Text("COMMANDS", style = MachineLabel, color = MachineColors.Admin) }
        }

        if (records.isEmpty()) {
            item {
                Text(
                    "NOTHING YET. HOLD THE SIDE BUTTON.",
                    style = MachineReadout,
                    color = MachineColors.Dim,
                )
            }
        }

        items(records, key = { it.atEpochMillis }) { record -> RecordRow(record) }
    }
}

/**
 * One learned shape, its numbers shown as the placeholder they are stored as.
 *
 * Tapping it forgets that one phrase. Without this the only correction available was
 * wiping every phrase the assistant knows.
 */
@Composable
private fun LearnedRow(entry: CommandCache.Entry, onForget: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onForget),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entry.tool.uppercase(), style = MachineReadout, color = MachineColors.Relevant)
            Text("${entry.hits} USES", style = MachineReadout, color = MachineColors.Dim)
            Text("TAP TO FORGET", style = MachineReadout, color = MachineColors.Ghost)
        }
        Text(entry.key, style = MachineStatus, color = MachineColors.Bone)
    }
}

@Composable
private fun RecordRow(record: QueryRecord) {
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
            record.tool?.let {
                Text(it.uppercase(), style = MachineReadout, color = MachineColors.Dim)
            }
        }

        // The command in the reading size, not the heading size: some of these are a whole
        // sentence, and at heading size a single one filled the screen.
        Text(record.transcript, style = MachineReadout, color = MachineColors.Bone)

        // What was actually done. For an answer the spoken line is the argument, so
        // printing both said everything twice.
        Text(
            text = record.spoken.ifBlank {
                record.arguments.entries.joinToString(" ") { "${it.key}=${it.value}" }
            },
            style = MachineStatus,
            color = if (record.success) MachineColors.Dim else MachineColors.Relevant,
        )
    }
}

private val TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.systemDefault())
