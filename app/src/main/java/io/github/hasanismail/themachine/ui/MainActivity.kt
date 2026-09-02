/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import io.github.hasanismail.themachine.BuildConfig
import io.github.hasanismail.themachine.R
import io.github.hasanismail.themachine.ui.machine.BootLine
import io.github.hasanismail.themachine.ui.machine.BootSequence
import io.github.hasanismail.themachine.ui.machine.IndeterminateCells
import io.github.hasanismail.themachine.ui.machine.MachineRule
import io.github.hasanismail.themachine.ui.machine.ScanSweep
import io.github.hasanismail.themachine.ui.machine.TrackingBox
import io.github.hasanismail.themachine.ui.machine.rememberSnapProgress
import io.github.hasanismail.themachine.ui.machine.scanlines
import io.github.hasanismail.themachine.ui.theme.MachineColors
import io.github.hasanismail.themachine.ui.theme.MachineLabel
import io.github.hasanismail.themachine.ui.theme.MachineReadout
import io.github.hasanismail.themachine.ui.theme.MachineStatus
import io.github.hasanismail.themachine.ui.theme.TheMachineTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TheMachineTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MachineColors.Void,
                ) { insets ->
                    HomeScreen(modifier = Modifier.padding(insets))
                }
            }
        }
    }
}

@Composable
internal fun HomeScreen(modifier: Modifier = Modifier) {
    var booted by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MachineColors.Void)
            .scanlines(),
    ) {
        ScanSweep(modifier = Modifier.fillMaxSize(), periodMillis = 4200)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Header()
            MachineRule(Modifier.fillMaxWidth().height(1.dp))
            Identity(locked = booted)
            SystemReadout(onComplete = { booted = true })
            Spacer(Modifier.weight(1f))
            AwaitingCommand(active = booted)
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ADMIN", style = MachineLabel, color = MachineColors.Admin)
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MachineLabel,
            color = MachineColors.Ghost,
        )
    }
}

/** The app's own name card, framed the way the system frames anything it is tracking. */
@Composable
private fun Identity(locked: Boolean) {
    val progress = rememberSnapProgress(locked, durationMillis = 260)
    TrackingBox(
        modifier = Modifier.fillMaxWidth(),
        color = MachineColors.Admin,
        progress = progress,
        cornerLength = 20.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                style = MachineStatus,
                color = MachineColors.Bone,
            )
            Text(
                text = stringResource(R.string.app_tagline).uppercase(),
                style = MachineLabel,
                color = MachineColors.Dim,
            )
        }
    }
}

@Composable
private fun SystemReadout(onComplete: () -> Unit) {
    // Facts the system can state about itself without loading a model.
    val lines = remember {
        listOf(
            BootLine("INTERFACE", "ONLINE"),
            BootLine("PLATFORM", "ANDROID ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"),
            BootLine("HOST", Build.MODEL.uppercase()),
            BootLine("ARCH", Build.SUPPORTED_ABIS.firstOrNull()?.uppercase() ?: "UNKNOWN"),
            BootLine("NETWORK", "NOT REQUIRED", MachineColors.Admin),
            BootLine("TELEMETRY", "NONE", MachineColors.Admin),
        )
    }
    BootSequence(
        lines = lines,
        modifier = Modifier.fillMaxWidth(),
        onComplete = onComplete,
    )
}

@Composable
private fun AwaitingCommand(active: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MachineRule(Modifier.fillMaxWidth().height(1.dp))
        Text(
            text = if (active) "AWAITING COMMAND" else "INITIALISING",
            style = MachineLabel,
            color = if (active) MachineColors.Asset else MachineColors.Dim,
        )
        IndeterminateCells(
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (active) MachineColors.Asset else MachineColors.Admin,
        )
        Text(
            text = "HOLD THE SIDE BUTTON TO SPEAK. VOICE PIPELINE ARRIVES IN A LATER PHASE.",
            style = MachineReadout,
            color = MachineColors.Ghost,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF05070A)
@Composable
private fun HomeScreenPreview() {
    TheMachineTheme { HomeScreen() }
}
