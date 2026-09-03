/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.ui.models

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.work.WorkInfo
import io.github.hasanismail.themachine.audio.MachineSounds
import io.github.hasanismail.themachine.models.ModelAsset
import io.github.hasanismail.themachine.models.ModelDownloadWorker
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.ui.machine.IndeterminateCells
import io.github.hasanismail.themachine.ui.machine.MachineRule
import io.github.hasanismail.themachine.ui.machine.TrackingBox
import io.github.hasanismail.themachine.ui.machine.rememberSnapProgress
import io.github.hasanismail.themachine.ui.machine.scanlines
import io.github.hasanismail.themachine.ui.theme.MachineColors
import io.github.hasanismail.themachine.ui.theme.MachineLabel
import io.github.hasanismail.themachine.ui.theme.MachineReadout

private const val BYTES_PER_MB = 1024.0 * 1024.0
private const val BYTES_PER_GB = BYTES_PER_MB * 1024
private const val PERCENT = 100

/** GB past a gigabyte, MB below it — "3009 MB" reads as noise next to "2.9 GB". */
private fun sizeLabel(bytes: Long): String =
    if (bytes >= BYTES_PER_GB) {
        "%.1f GB".format(bytes / BYTES_PER_GB)
    } else {
        "%.0f MB".format(bytes / BYTES_PER_MB)
    }

/**
 * Choose and download the speech, language and voice models.
 *
 * Live progress comes from WorkManager rather than from a download object held here, so
 * the screen shows the truth even if it was closed and reopened halfway through a
 * gigabyte.
 */
@Composable
fun ModelManagerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val registry = remember(context) { ModelRegistry(context) }
    val storage = remember(context) { ModelStorage(context) }

    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }

    val progress = remember { mutableStateMapOf<String, Pair<Long, Long>>() }
    val failures = remember { mutableStateMapOf<String, String>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MachineColors.Void)
            .scanlines()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("MODELS", style = MachineLabel, color = MachineColors.Admin)
            Text(
                text = sizeLabel(storage.freeBytes()) + " FREE",
                style = MachineLabel,
                color = MachineColors.Dim,
            )
        }
        MachineRule(Modifier.fillMaxWidth().height(1.dp))

        Text(
            text = "Downloaded once, then never again. Every file is checked against a " +
                "SHA-256 the app already knows, and an interrupted download picks up where " +
                "it stopped.",
            style = MachineReadout,
            color = MachineColors.Ghost,
        )

        for (role in ModelRole.entries) {
            val assets = registry.byRole(role)
            if (assets.isEmpty()) continue
            Text(
                text = role.heading(),
                style = MachineLabel,
                color = MachineColors.Ghost,
                modifier = Modifier.padding(top = 6.dp),
            )
            for (asset in assets) {
                ModelRow(
                    asset = asset,
                    state = remember(revision, progress[asset.id]) { storage.quickState(asset) },
                    live = progress[asset.id],
                    error = failures[asset.id],
                    onDownload = {
                        MachineSounds.play(MachineSounds.Cue.TICK, volume = 0.3f)
                        failures.remove(asset.id)
                        ModelDownloadWorker.enqueue(context, asset.id)
                    },
                    onCancel = {
                        ModelDownloadWorker.cancel(context, asset.id)
                        progress.remove(asset.id)
                        revision++
                    },
                    onDelete = {
                        storage.delete(asset)
                        progress.remove(asset.id)
                        revision++
                    },
                )

                // One collector per asset, so a finished download repaints its own row.
                // remember()ed because observe() builds a fresh Flow on every call.
                val workFlow = remember(asset.id) { ModelDownloadWorker.observe(context, asset.id) }
                val work by workFlow.collectAsState(initial = null)
                LaunchedEffect(work?.state, work?.progress) {
                    val info = work ?: return@LaunchedEffect
                    when (info.state) {
                        WorkInfo.State.RUNNING -> {
                            val done = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED, 0)
                            val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, asset.byteSize)
                            if (done > 0) progress[asset.id] = done to total
                        }

                        WorkInfo.State.SUCCEEDED -> {
                            progress.remove(asset.id)
                            revision++
                            MachineSounds.play(MachineSounds.Cue.CONFIRM, volume = 0.4f)
                        }

                        WorkInfo.State.FAILED -> {
                            progress.remove(asset.id)
                            failures[asset.id] =
                                info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                                    ?: "Download failed"
                            revision++
                            MachineSounds.play(MachineSounds.Cue.REJECT, volume = 0.4f)
                        }

                        else -> Unit
                    }
                }
            }
        }
    }
}

private fun ModelRole.heading(): String = when (this) {
    ModelRole.STT -> "SPEECH RECOGNITION"
    ModelRole.LLM -> "LANGUAGE MODEL"
    ModelRole.TTS -> "VOICE"
    ModelRole.OCR -> "SCREEN READING"
    ModelRole.WAKE -> "WAKE WORD"
}

@Composable
private fun ModelRow(
    asset: ModelAsset,
    state: ModelState,
    live: Pair<Long, Long>?,
    error: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val ready = state is ModelState.Ready
    val downloading = live != null
    val tone = when {
        error != null -> MachineColors.Relevant
        ready -> MachineColors.Asset
        downloading -> MachineColors.Admin
        else -> MachineColors.Dim
    }

    TrackingBox(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !downloading) { if (ready) onDelete() else onDownload() },
        color = tone,
        progress = rememberSnapProgress(locked = ready),
        cornerLength = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // weight(1f) on the title and softWrap=false on the size: without it a
                // long name squeezes the size column down to one character per line.
                Text(
                    text = asset.displayName.uppercase(),
                    style = MachineLabel,
                    color = MachineColors.Bone,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = sizeLabel(asset.byteSize),
                    style = MachineLabel,
                    color = MachineColors.Dim,
                    softWrap = false,
                    maxLines = 1,
                )
            }
            if (asset.isDefault) {
                Text("DEFAULT", style = MachineLabel, color = MachineColors.Asset)
            }

            Text(asset.detail, style = MachineReadout, color = MachineColors.Dim)

            when {
                error != null -> Text(error.uppercase(), style = MachineLabel, color = MachineColors.Relevant)

                downloading -> {
                    val (done, total) = live
                    val pct = if (total > 0) (done * PERCENT / total).toInt() else 0
                    // Interpolated rather than String.format: the literal '%' after $pct
                    // would otherwise be parsed as a format flag and throw
                    // DuplicateFormatFlagsException at runtime.
                    Text(
                        text = "$pct%   ${sizeLabel(done)} / ${sizeLabel(total)}",
                        style = MachineLabel,
                        color = MachineColors.Admin,
                    )
                    ProgressBar(fraction = if (total > 0) done.toFloat() / total else 0f)
                    Text("TAP CANCEL BELOW", style = MachineLabel, color = MachineColors.Ghost)
                    Text(
                        text = "CANCEL →",
                        style = MachineLabel,
                        color = MachineColors.Relevant,
                        modifier = Modifier.clickable(onClick = onCancel),
                    )
                }

                ready -> Text("INSTALLED  ·  TAP TO DELETE", style = MachineLabel, color = MachineColors.Asset)

                state is ModelState.Partial -> {
                    Text(
                        text = "PARTIAL ${sizeLabel(state.downloadedBytes)}  ·  TAP TO RESUME",
                        style = MachineLabel,
                        color = MachineColors.Admin,
                    )
                }

                state is ModelState.Corrupt ->
                    Text("CORRUPT · TAP TO RE-DOWNLOAD", style = MachineLabel, color = MachineColors.Relevant)

                else -> Text("TAP TO DOWNLOAD →", style = MachineLabel, color = MachineColors.Admin)
            }
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(MachineColors.Rule),
    ) {
        if (fraction <= 0f) {
            IndeterminateCells(Modifier.fillMaxWidth().height(6.dp))
        } else {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(MachineColors.Admin),
            )
        }
    }
}
