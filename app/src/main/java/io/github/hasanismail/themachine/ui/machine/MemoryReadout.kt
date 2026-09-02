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

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import io.github.hasanismail.themachine.ui.theme.MachineColors
import io.github.hasanismail.themachine.ui.theme.MachineLabel
import kotlinx.coroutines.delay

private const val KB = 1024L
private const val MB = KB * 1024
private const val GB = MB * 1024

/** What the process and the device are using right now. */
data class MemorySnapshot(
    /** Everything this process holds — Java heap, native heap, mmapped models, graphics. */
    val appPssBytes: Long,
    val deviceUsedBytes: Long,
    val deviceTotalBytes: Long,
    /** True once the system considers memory tight enough to start killing things. */
    val deviceLowMemory: Boolean,
) {
    val devicePercent: Int
        get() = if (deviceTotalBytes > 0) ((deviceUsedBytes * PERCENT) / deviceTotalBytes).toInt() else 0

    private companion object {
        const val PERCENT = 100
    }
}

/**
 * Reads memory for the current process and the device.
 *
 * PSS rather than the Java heap: this app's memory is mostly a memory-mapped GGUF that
 * never appears in Runtime.totalMemory(), so heap figures would report a few tens of MB
 * while a gigabyte of model was resident.
 */
fun readMemory(context: Context): MemorySnapshot {
    val activityManager = context.getSystemService<ActivityManager>()
    val info = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }

    val pssKb = runCatching {
        Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong()
    }.getOrDefault(0L)

    return MemorySnapshot(
        appPssBytes = pssKb * KB,
        deviceUsedBytes = (info.totalMem - info.availMem).coerceAtLeast(0),
        deviceTotalBytes = info.totalMem,
        deviceLowMemory = info.lowMemory,
    )
}

/**
 * A live memory readout.
 *
 * Worth having on screen permanently for this app rather than buried in a debug menu:
 * the whole design question is whether a 1B model and Whisper can coexist in RAM on the
 * phone, and the answer changes as models are loaded and freed.
 */
@Composable
fun MemoryReadout(
    context: Context,
    modifier: Modifier = Modifier,
    refreshMillis: Long = 1500,
) {
    var snapshot by remember { mutableStateOf(readMemory(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(refreshMillis)
            snapshot = readMemory(context)
        }
    }

    val tone = when {
        snapshot.deviceLowMemory -> MachineColors.Relevant
        snapshot.devicePercent >= HIGH_WATER -> MachineColors.Admin
        else -> MachineColors.Asset
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("MEMORY", style = MachineLabel, color = MachineColors.Dim)
            Text(
                text = "${formatBytes(snapshot.appPssBytes)} APP",
                style = MachineLabel,
                color = tone,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("DEVICE", style = MachineLabel, color = MachineColors.Dim)
            Text(
                text = "${formatBytes(snapshot.deviceUsedBytes)} / " +
                    "${formatBytes(snapshot.deviceTotalBytes)}  ${snapshot.devicePercent}%",
                style = MachineLabel,
                color = tone,
            )
        }
        MemoryBar(fraction = snapshot.devicePercent / PERCENT_F, tone = tone)
    }
}

@Composable
private fun MemoryBar(fraction: Float, tone: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(MachineColors.Rule),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(tone),
        )
    }
}

private const val HIGH_WATER = 80
private const val PERCENT_F = 100f

private fun formatBytes(bytes: Long): String = when {
    bytes >= GB -> "%.1f GB".format(bytes.toDouble() / GB)
    else -> "%.0f MB".format(bytes.toDouble() / MB)
}
