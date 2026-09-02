/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.ui.debug

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.hasanismail.themachine.audio.MachineSounds
import io.github.hasanismail.themachine.nativebridge.NativeBridge
import io.github.hasanismail.themachine.nativebridge.NativeBuildInfo
import io.github.hasanismail.themachine.ui.machine.IndeterminateCells
import io.github.hasanismail.themachine.ui.machine.MachineRule
import io.github.hasanismail.themachine.ui.machine.TrackingBox
import io.github.hasanismail.themachine.ui.machine.rememberSnapProgress
import io.github.hasanismail.themachine.ui.machine.scanlines
import io.github.hasanismail.themachine.ui.theme.MachineColors
import io.github.hasanismail.themachine.ui.theme.MachineDump
import io.github.hasanismail.themachine.ui.theme.MachineLabel
import io.github.hasanismail.themachine.ui.theme.MachineReadout
import io.github.hasanismail.themachine.ui.theme.TheMachineTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P1 acceptance surface: proves on a real device that both native engines linked,
 * loaded, and selected a CPU backend. Read-only diagnostics.
 */
class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TheMachineTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MachineColors.Void,
                ) { insets ->
                    DebugScreen(modifier = Modifier.padding(insets))
                }
            }
        }
    }
}

@Composable
private fun DebugScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Loading the native library and dlopen-ing the backend variants is not main-thread
    // work, even though it is fast.
    val info by produceState<NativeBuildInfo?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { NativeBridge.buildInfo(context) }
    }

    LaunchedEffect(info?.loadError, info != null) {
        val snapshot = info ?: return@LaunchedEffect
        MachineSounds.play(
            if (snapshot.loadError == null) MachineSounds.Cue.CONFIRM else MachineSounds.Cue.REJECT,
        )
    }

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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ANALOG INTERFACE", style = MachineLabel, color = MachineColors.Admin)
            Text("DIAGNOSTIC", style = MachineLabel, color = MachineColors.Ghost)
        }
        MachineRule(Modifier.fillMaxWidth().height(1.dp))

        val snapshot = info
        if (snapshot == null) {
            Text("INTERROGATING NATIVE LAYER", style = MachineLabel, color = MachineColors.Dim)
            IndeterminateCells(Modifier.fillMaxWidth().height(6.dp))
            return@Column
        }

        val error = snapshot.loadError
        if (error != null) {
            Panel("LOAD FAILED", error, MachineColors.Relevant)
            return@Column
        }

        Panel(
            title = "HOST",
            body = buildString {
                appendLine("MODEL      ${Build.MANUFACTURER.uppercase()} ${Build.MODEL}")
                appendLine("ANDROID    ${Build.VERSION.RELEASE}  API ${Build.VERSION.SDK_INT}")
                appendLine("ABI        ${Build.SUPPORTED_ABIS.joinToString()}")
                append("PAGE       ${snapshot.pageSizeBytes} B  ")
                append(
                    if (snapshot.is16KbPageDevice) {
                        "(16 KB DEVICE)"
                    } else {
                        "(4 KB — WILL NOT CATCH ALIGNMENT REGRESSIONS)"
                    },
                )
            },
            tone = MachineColors.Irrelevant,
        )

        Panel(
            title = "ENGINES",
            body = buildString {
                appendLine("WHISPER    ${snapshot.whisperVersion}")
                appendLine("LLAMA      ${snapshot.llamaVersion}")
                append("MMAP       ${if (snapshot.supportsMmap) "SUPPORTED" else "UNSUPPORTED"}")
            },
            tone = MachineColors.Asset,
        )

        Panel(
            title = "GGML BACKENDS — ${snapshot.backendCount} REGISTERED",
            body = snapshot.backendReport.trimEnd().uppercase(),
            tone = if (snapshot.backendCount == 0) MachineColors.Relevant else MachineColors.Asset,
        )

        Panel("WHISPER CPU", snapshot.whisperSystemInfo.trimEnd(), MachineColors.Irrelevant)
        Panel("LLAMA CPU", snapshot.llamaSystemInfo.trimEnd(), MachineColors.Irrelevant)

        LanguageModelProbe()
    }
}

/**
 * Types a command straight into the parsing half of the pipeline, skipping speech.
 *
 * The prompt and grammar are what need iterating most, and re-recording the same
 * sentence into a microphone is a slow way to find out how the model reads it.
 */
@Composable
private fun LanguageModelProbe() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val probe = remember { LlmProbe(context) }
    DisposableEffect(Unit) { onDispose { probe.release() } }

    var input by remember { mutableStateOf("set an alarm for 7am") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProbeResult?>(null) }

    TrackingBox(
        modifier = Modifier.fillMaxWidth(),
        color = MachineColors.Admin,
        progress = rememberSnapProgress(locked = true),
        filled = true,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("LANGUAGE MODEL PROBE", style = MachineLabel, color = MachineColors.Admin)
            Box(Modifier.fillMaxWidth().height(1.dp).background(MachineColors.Rule))

            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = MachineReadout.merge(TextStyle(color = MachineColors.Bone)),
                cursorBrush = SolidColor(MachineColors.Admin),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MachineColors.PanelActive)
                    .padding(8.dp),
            )

            Text(
                text = if (busy) "RUNNING…" else "TAP TO PARSE →",
                style = MachineLabel,
                color = if (busy) MachineColors.Ghost else MachineColors.Admin,
                modifier = Modifier.clickable(enabled = !busy) {
                    busy = true
                    scope.launch {
                        result = probe.run(input)
                        busy = false
                    }
                },
            )

            result?.let { r ->
                val tone = when {
                    r.error != null -> MachineColors.Relevant
                    r.call != null -> MachineColors.Asset
                    else -> MachineColors.Relevant
                }
                Text(
                    text = r.error ?: (r.call?.let { "${it.tool}  ${it.arguments}" } ?: "UNPARSED"),
                    style = MachineReadout,
                    color = tone,
                )
                Text("${r.millis} MS", style = MachineLabel, color = MachineColors.Dim)
                SelectionContainer {
                    Text(r.raw.ifBlank { "(no output)" }, style = MachineDump, color = MachineColors.Dim)
                }
            }
        }
    }
}

@Composable
private fun Panel(
    title: String,
    body: String,
    tone: Color,
) {
    // Each panel locks on as it appears, the way the system frames anything it is
    // reporting about.
    val progress = rememberSnapProgress(locked = true, durationMillis = 220)
    TrackingBox(
        modifier = Modifier.fillMaxWidth(),
        color = tone,
        progress = progress,
        filled = true,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, style = MachineLabel, color = tone)
            Box(Modifier.fillMaxWidth().height(1.dp).background(MachineColors.Rule))
            // Selectable so values can be copied straight into a bug report.
            SelectionContainer {
                Text(
                    text = body.ifBlank { "(EMPTY)" },
                    style = if (body.length > 120) MachineDump else MachineReadout,
                    color = MachineColors.Bone,
                )
            }
        }
    }
}
