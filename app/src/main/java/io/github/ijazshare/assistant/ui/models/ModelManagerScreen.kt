/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.ui.models

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
import io.github.ijazshare.assistant.audio.AssistantSounds
import io.github.ijazshare.assistant.models.ModelAsset
import io.github.ijazshare.assistant.models.ModelDownloadWorker
import io.github.ijazshare.assistant.models.ModelRegistry
import io.github.ijazshare.assistant.models.ModelRole
import io.github.ijazshare.assistant.models.ModelState
import io.github.ijazshare.assistant.models.ModelStorage
import io.github.ijazshare.assistant.ui.assistant.AssistantRule
import io.github.ijazshare.assistant.ui.assistant.IndeterminateCells
import io.github.ijazshare.assistant.ui.assistant.TrackingBox
import io.github.ijazshare.assistant.ui.assistant.rememberSnapProgress
import io.github.ijazshare.assistant.ui.assistant.scanlines
import io.github.ijazshare.assistant.ui.theme.AssistantColors
import io.github.ijazshare.assistant.ui.theme.AssistantLabel
import io.github.ijazshare.assistant.ui.theme.AssistantReadout

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
            .background(AssistantColors.Void)
            .scanlines()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("MODELS", style = AssistantLabel, color = AssistantColors.Admin)
            Text(
                text = sizeLabel(storage.freeBytes()) + " FREE",
                style = AssistantLabel,
                color = AssistantColors.Dim,
            )
        }
        AssistantRule(Modifier.fillMaxWidth().height(1.dp))

        Text(
            text = "Downloaded once, then never again. Every file is checked against a " +
                "SHA-256 the app already knows, and an interrupted download picks up where " +
                "it stopped.",
            style = AssistantReadout,
            color = AssistantColors.Ghost,
        )

        for (role in ModelRole.entries) {
            val assets = registry.byRole(role)
            if (assets.isEmpty()) continue
            Text(
                text = role.heading(),
                style = AssistantLabel,
                color = AssistantColors.Ghost,
                modifier = Modifier.padding(top = 6.dp),
            )
            for (asset in assets) {
                ModelRow(
                    asset = asset,
                    state = remember(revision, progress[asset.id]) { storage.quickState(asset) },
                    live = progress[asset.id],
                    error = failures[asset.id],
                    onDownload = {
                        AssistantSounds.play(AssistantSounds.Cue.TICK, volume = 0.3f)
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
                            AssistantSounds.play(AssistantSounds.Cue.CONFIRM, volume = 0.4f)
                        }

                        WorkInfo.State.FAILED -> {
                            progress.remove(asset.id)
                            failures[asset.id] =
                                info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                                    ?: "Download failed"
                            revision++
                            AssistantSounds.play(AssistantSounds.Cue.REJECT, volume = 0.4f)
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
    ModelRole.VAD -> "END OF SPEECH"
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
        error != null -> AssistantColors.Relevant
        ready -> AssistantColors.Asset
        downloading -> AssistantColors.Admin
        else -> AssistantColors.Dim
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
                    style = AssistantLabel,
                    color = AssistantColors.Bone,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = sizeLabel(asset.byteSize),
                    style = AssistantLabel,
                    color = AssistantColors.Dim,
                    softWrap = false,
                    maxLines = 1,
                )
            }
            if (asset.isDefault) {
                Text("DEFAULT", style = AssistantLabel, color = AssistantColors.Asset)
            }

            Text(asset.detail, style = AssistantReadout, color = AssistantColors.Dim)

            when {
                error != null -> Text(error.uppercase(), style = AssistantLabel, color = AssistantColors.Relevant)

                downloading -> {
                    val (done, total) = live
                    val pct = if (total > 0) (done * PERCENT / total).toInt() else 0
                    // Interpolated rather than String.format: the literal '%' after $pct
                    // would otherwise be parsed as a format flag and throw
                    // DuplicateFormatFlagsException at runtime.
                    Text(
                        text = "$pct%   ${sizeLabel(done)} / ${sizeLabel(total)}",
                        style = AssistantLabel,
                        color = AssistantColors.Admin,
                    )
                    ProgressBar(fraction = if (total > 0) done.toFloat() / total else 0f)
                    Text("TAP CANCEL BELOW", style = AssistantLabel, color = AssistantColors.Ghost)
                    Text(
                        text = "CANCEL →",
                        style = AssistantLabel,
                        color = AssistantColors.Relevant,
                        modifier = Modifier.clickable(onClick = onCancel),
                    )
                }

                ready -> Text("INSTALLED  ·  TAP TO DELETE", style = AssistantLabel, color = AssistantColors.Asset)

                state is ModelState.Partial -> {
                    Text(
                        text = "PARTIAL ${sizeLabel(state.downloadedBytes)}  ·  TAP TO RESUME",
                        style = AssistantLabel,
                        color = AssistantColors.Admin,
                    )
                }

                state is ModelState.Corrupt ->
                    Text("CORRUPT · TAP TO RE-DOWNLOAD", style = AssistantLabel, color = AssistantColors.Relevant)

                else -> Text("TAP TO DOWNLOAD →", style = AssistantLabel, color = AssistantColors.Admin)
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
            .background(AssistantColors.Rule),
    ) {
        if (fraction <= 0f) {
            IndeterminateCells(Modifier.fillMaxWidth().height(6.dp))
        } else {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(AssistantColors.Admin),
            )
        }
    }
}
