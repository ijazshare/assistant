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

import android.content.Intent
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.ijazshare.assistant.BuildConfig
import io.github.ijazshare.assistant.R
import io.github.ijazshare.assistant.context.AssistantFiles
import io.github.ijazshare.assistant.models.ModelRegistry
import io.github.ijazshare.assistant.models.ModelState
import io.github.ijazshare.assistant.models.ModelStorage
import io.github.ijazshare.assistant.permissions.AssistantPermissions
import io.github.ijazshare.assistant.permissions.PermissionInspector
import io.github.ijazshare.assistant.settings.AssistantSettings
import io.github.ijazshare.assistant.tools.ReminderReceiver
import io.github.ijazshare.assistant.ui.assistant.AssistantRule
import io.github.ijazshare.assistant.ui.assistant.BootLine
import io.github.ijazshare.assistant.ui.assistant.BootSequence
import io.github.ijazshare.assistant.ui.assistant.MemoryReadout
import io.github.ijazshare.assistant.ui.assistant.ScanSweep
import io.github.ijazshare.assistant.ui.assistant.TrackingBox
import io.github.ijazshare.assistant.ui.assistant.rememberSnapProgress
import io.github.ijazshare.assistant.ui.assistant.scanlines
import io.github.ijazshare.assistant.ui.theme.AssistantColors
import io.github.ijazshare.assistant.ui.theme.AssistantLabel
import io.github.ijazshare.assistant.ui.theme.AssistantReadout
import io.github.ijazshare.assistant.ui.theme.AssistantStatus
import io.github.ijazshare.assistant.ui.theme.AssistantTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Opened from a reminder's notification: seen, so a reboot does not show it again.
        intent.getStringExtra(ReminderReceiver.EXTRA_SEEN)?.let { id ->
            Thread { runCatching { AssistantFiles(this).markSeen(id) } }.start()
        }
        setContent {
            AssistantTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = AssistantColors.Void,
                ) { insets ->
                    HomeScreen(modifier = Modifier.padding(insets))
                }
            }
        }
    }
}

/**
 * Deliberately sparse: identity, a three-line readout, and two doors. Anything with a
 * long list behind it — permissions, models — lives on its own page, so this one can be
 * taken in at a glance instead of scrolled.
 */
@Composable
internal fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var booted by remember { mutableStateOf(false) }

    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }

    val access = remember(revision) {
        val inspector = PermissionInspector(context)
        AssistantPermissions.all.count { inspector.state(it).isSettled } to
            AssistantPermissions.all.size
    }
    // Collected, not read with runBlocking. Reading a preference that way inside
    // composition blocks the main thread on a disk read, which on a cold start is how an
    // app stops responding before it has drawn anything.
    val settings = remember { AssistantSettings(context) }
    val adminName by settings.adminName.collectAsStateWithLifecycle(
        initialValue = AssistantSettings.DEFAULT_ADMIN_NAME,
    )
    val models = remember(revision) {
        val registry = ModelRegistry(context)
        val storage = ModelStorage(context)
        // Counts roles covered, not defaults present — choosing base.en over tiny.en
        // still leaves speech recognition fully working.
        registry.rolesSatisfied { storage.quickState(it) == ModelState.Ready }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AssistantColors.Void)
            .scanlines(),
    ) {
        ScanSweep(modifier = Modifier.fillMaxSize(), periodMillis = 4200)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ADMIN", style = AssistantLabel, color = AssistantColors.Admin)
                Text("v${BuildConfig.VERSION_NAME}", style = AssistantLabel, color = AssistantColors.Ghost)
            }
            AssistantRule(Modifier.fillMaxWidth().height(1.dp))

            Identity(locked = booted)

            BootSequence(
                lines = remember {
                    listOf(
                        BootLine("INTERFACE", "ONLINE"),
                        BootLine("NETWORK", "NOT REQUIRED", AssistantColors.Admin),
                        BootLine("TELEMETRY", "NONE", AssistantColors.Admin),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                onComplete = { booted = true },
            )

            MemoryReadout(context = context, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(2.dp))

            Door(
                label = "SYSTEM ACCESS",
                value = "${access.first} / ${access.second}",
                complete = access.first == access.second,
                onClick = { context.startActivity(Intent(context, PermissionsActivity::class.java)) },
            )
            Door(
                label = "CONTEXT",
                value = adminName.uppercase(),
                complete = true,
                onClick = { context.startActivity(Intent(context, ContextActivity::class.java)) },
            )
            Door(
                label = "HISTORY",
                value = "LOG",
                complete = true,
                onClick = { context.startActivity(Intent(context, HistoryActivity::class.java)) },
            )
            Door(
                label = "MODELS",
                value = "${models.first} / ${models.second}",
                complete = models.first == models.second,
                onClick = { context.startActivity(Intent(context, ModelsActivity::class.java)) },
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = if (models.first == models.second) {
                    "HOLD THE SIDE BUTTON TO SPEAK."
                } else {
                    "DOWNLOAD THE MODELS TO BEGIN."
                },
                style = AssistantReadout,
                color = AssistantColors.Ghost,
            )
        }
    }
}

/** The app's own name card, framed the way the system frames anything it tracks. */
@Composable
private fun Identity(locked: Boolean) {
    TrackingBox(
        modifier = Modifier.fillMaxWidth(),
        color = AssistantColors.Admin,
        progress = rememberSnapProgress(locked, durationMillis = 260),
        cornerLength = 20.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                style = AssistantStatus,
                color = AssistantColors.Bone,
            )
            Text(
                text = stringResource(R.string.app_tagline).uppercase(),
                style = AssistantLabel,
                color = AssistantColors.Dim,
            )
        }
    }
}

/** A row that opens another page, carrying the one number that matters. */
@Composable
private fun Door(
    label: String,
    value: String,
    complete: Boolean,
    onClick: () -> Unit,
) {
    val tone = if (complete) AssistantColors.Asset else AssistantColors.Admin
    TrackingBox(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = tone,
        progress = rememberSnapProgress(locked = complete),
        cornerLength = 12.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = AssistantLabel, color = AssistantColors.Bone)
            Text("$value   →", style = AssistantLabel, color = tone)
        }
    }
}
