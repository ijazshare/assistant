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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hasanismail.themachine.nativebridge.NativeBridge
import io.github.hasanismail.themachine.nativebridge.NativeBuildInfo
import io.github.hasanismail.themachine.ui.theme.TheMachineTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P1 acceptance surface: proves on a real device that both native engines linked,
 * loaded, and selected a CPU backend. Everything here is read-only diagnostics.
 */
class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TheMachineTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
                    DebugScreen(modifier = Modifier.padding(insets))
                }
            }
        }
    }
}

@Composable
private fun DebugScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Loading the native library and dlopen-ing seven backend variants is not
    // main-thread work, even though it is fast.
    val info by produceState<NativeBuildInfo?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { NativeBridge.buildInfo(context) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Native bridge", style = MaterialTheme.typography.headlineSmall)

        val snapshot = info
        if (snapshot == null) {
            Text("Loading native libraries…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        val error = snapshot.loadError
        if (error != null) {
            Section("Load failed", error, isError = true)
            return@Column
        }

        Section(
            title = "Device",
            body = buildString {
                appendLine("model:      ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android:    ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("abis:       ${Build.SUPPORTED_ABIS.joinToString()}")
                appendLine("page size:  ${snapshot.pageSizeBytes} bytes")
                append("            ")
                append(
                    if (snapshot.is16KbPageDevice) {
                        "16 KB device — libraries must be 16 KB aligned"
                    } else {
                        "4 KB device — will NOT catch a 16 KB alignment regression"
                    },
                )
            },
        )

        Section(
            title = "Versions",
            body = buildString {
                appendLine("whisper.cpp:  ${snapshot.whisperVersion}")
                appendLine("llama.cpp:    ${snapshot.llamaVersion}")
                append("mmap:         ${if (snapshot.supportsMmap) "supported" else "UNSUPPORTED"}")
            },
        )

        Section(
            title = "ggml backends (${snapshot.backendCount} registered)",
            body = snapshot.backendReport.trimEnd(),
            isError = snapshot.backendCount == 0,
        )

        Section("whisper system info", snapshot.whisperSystemInfo.trimEnd())
        Section("llama system info", snapshot.llamaSystemInfo.trimEnd())
    }
}

@Composable
private fun Section(
    title: String,
    body: String,
    isError: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isError) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // Selectable so the values can be copied straight into a bug report.
            SelectionContainer {
                Text(
                    text = body.ifBlank { "(empty)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
