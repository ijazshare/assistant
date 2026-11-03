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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ijazshare.assistant.R
import io.github.ijazshare.assistant.context.AssistantFiles
import io.github.ijazshare.assistant.history.QueryLog
import io.github.ijazshare.assistant.models.ModelRegistry
import io.github.ijazshare.assistant.models.ModelState
import io.github.ijazshare.assistant.models.ModelStorage
import io.github.ijazshare.assistant.permissions.AssistantPermissions
import io.github.ijazshare.assistant.permissions.PermissionInspector
import io.github.ijazshare.assistant.settings.AssistantSettings
import io.github.ijazshare.assistant.tools.ReminderReceiver
import io.github.ijazshare.assistant.ui.assistant.MemoryReadout
import io.github.ijazshare.assistant.ui.assistant.TrackingBox
import io.github.ijazshare.assistant.ui.assistant.scanlines
import io.github.ijazshare.assistant.ui.theme.AssistantColors
import io.github.ijazshare.assistant.ui.theme.AssistantLabel
import io.github.ijazshare.assistant.ui.theme.AssistantReadout
import io.github.ijazshare.assistant.ui.theme.AssistantTheme
import io.github.ijazshare.assistant.ui.theme.HaloLabel
import io.github.ijazshare.assistant.ui.theme.HaloTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

/** What Home shows; plain values so the screen can be drawn without a phone behind it. */
internal data class HomeState(
    val accessGranted: Int,
    val accessTotal: Int,
    val modelsReady: Int,
    val modelsTotal: Int,
    val adminName: String,
    /** When the last command ran, or null while there is none. */
    val lastCommandMillis: Long?,
) {
    val accessMissing: Boolean get() = accessGranted < accessTotal
    val modelsComplete: Boolean get() = modelsReady == modelsTotal
}

/** The four places Home leads to. */
internal enum class HomeDoor { HISTORY, MODELS, CONTEXT, ACCESS }

/**
 * Sparse on purpose: the app's name, the memory readout, and a few doors in the order they
 * are used. Every readout varies (last command, models installed, access granted), and
 * System access is a banner only while something is missing.
 */
@Composable
internal fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

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
        // Counts roles covered, not defaults present: choosing base.en over tiny.en
        // still leaves speech recognition fully working.
        registry.rolesSatisfied { storage.quickState(it) == ModelState.Ready }
    }
    // Read off the main thread: the log is a file.
    val lastCommand by produceState<Long?>(null, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching { QueryLog(context).recent(1).firstOrNull()?.atEpochMillis }.getOrNull()
        }
    }

    HomeContent(
        state = HomeState(access.first, access.second, models.first, models.second, adminName, lastCommand),
        onOpen = { door ->
            val target = when (door) {
                HomeDoor.HISTORY -> HistoryActivity::class.java
                HomeDoor.MODELS -> ModelsActivity::class.java
                HomeDoor.CONTEXT -> ContextActivity::class.java
                HomeDoor.ACCESS -> PermissionsActivity::class.java
            }
            context.startActivity(Intent(context, target))
        },
        modifier = modifier,
    )
}

@Composable
internal fun HomeContent(
    state: HomeState,
    onOpen: (HomeDoor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AssistantColors.Void)
            .scanlines(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (state.accessMissing) {
                AccessBanner(state) { onOpen(HomeDoor.ACCESS) }
            }

            Identity()

            MemoryReadout(context = context, modifier = Modifier.fillMaxWidth())

            Door(
                label = "HISTORY",
                value = state.lastCommandMillis?.let { LAST.format(Instant.ofEpochMilli(it)).uppercase() }
                    ?: "NO COMMANDS YET",
                tone = AssistantColors.Admin,
                onClick = { onOpen(HomeDoor.HISTORY) },
            )
            Door(
                label = "MODELS",
                value = "${state.modelsReady} / ${state.modelsTotal}",
                tone = if (state.modelsComplete) AssistantColors.Asset else AssistantColors.Relevant,
                onClick = { onOpen(HomeDoor.MODELS) },
            )
            Door(
                label = "CONTEXT",
                value = state.adminName.uppercase(),
                tone = AssistantColors.Admin,
                onClick = { onOpen(HomeDoor.CONTEXT) },
            )
            if (!state.accessMissing) {
                Door(
                    label = "SYSTEM ACCESS",
                    value = "${state.accessGranted} / ${state.accessTotal}",
                    tone = AssistantColors.Asset,
                    onClick = { onOpen(HomeDoor.ACCESS) },
                )
            }

            Text(
                text = if (state.modelsComplete) {
                    "HOLD THE SIDE BUTTON TO SPEAK."
                } else {
                    "DOWNLOAD THE MODELS TO BEGIN."
                },
                style = AssistantReadout,
                color = AssistantColors.Dim,
            )
        }
    }
}

private val LAST: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.systemDefault())

/** Shown only while access is incomplete; says so in words as well as orange. */
@Composable
private fun AccessBanner(state: HomeState, onClick: () -> Unit) {
    TrackingBox(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("ACCESS MISSING", style = HaloLabel, color = AssistantColors.Relevant)
                Text(
                    text = "${state.accessGranted} of ${state.accessTotal} granted. Tap to finish.",
                    style = AssistantReadout,
                    color = AssistantColors.Bone,
                )
            }
            Text("›", style = HaloTitle, color = AssistantColors.Relevant)
        }
    }
}

/** The app's own name card, framed the way the system frames anything it tracks. */
@Composable
private fun Identity() {
    TrackingBox(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                style = HaloTitle,
                color = AssistantColors.Bone,
                modifier = Modifier.heading(),
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
    tone: Color,
    onClick: () -> Unit,
) {
    TrackingBox(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = HaloLabel, color = AssistantColors.Bone, modifier = Modifier.weight(1f))
            Text(value, style = HaloLabel, color = tone, textAlign = TextAlign.End)
            Text("›", style = HaloTitle, color = AssistantColors.Dim)
        }
    }
}
