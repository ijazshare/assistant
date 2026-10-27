/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.ui.onboarding

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.ijazshare.assistant.audio.AssistantSounds
import io.github.ijazshare.assistant.permissions.AssistantPermission
import io.github.ijazshare.assistant.permissions.AssistantPermissions
import io.github.ijazshare.assistant.permissions.GrantMechanism
import io.github.ijazshare.assistant.permissions.PermissionInspector
import io.github.ijazshare.assistant.permissions.PermissionState
import io.github.ijazshare.assistant.permissions.PermissionTier
import io.github.ijazshare.assistant.ui.machine.AssistantRule
import io.github.ijazshare.assistant.ui.machine.TrackingBox
import io.github.ijazshare.assistant.ui.machine.rememberSnapProgress
import io.github.ijazshare.assistant.ui.theme.AssistantColors
import io.github.ijazshare.assistant.ui.theme.AssistantLabel
import io.github.ijazshare.assistant.ui.theme.AssistantReadout

/**
 * The access checklist: every capability the assistant wants, what state it is in, and
 * a way to grant it.
 *
 * Refreshes on resume rather than polling, because most of these are granted on a
 * Settings screen the user has to walk to and back from — there is no result callback
 * for "the user toggled accessibility two screens deep".
 */
@Composable
fun AccessChecklist(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val inspector = remember(context) { PermissionInspector(context) }

    // Bumping this re-reads every state; cheaper and simpler than holding a map.
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }

    var pending by remember { mutableStateOf<AssistantPermission?>(null) }
    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        revision++
        val granted = results.values.any { it }
        AssistantSounds.play(
            if (granted) AssistantSounds.Cue.CONFIRM else AssistantSounds.Cue.REJECT,
            volume = 0.4f,
        )
        // A runtime permission that comes back denied without the dialog having been
        // shown means the platform has stopped asking. Send the user to app settings
        // instead of re-requesting into a no-op.
        val permission = pending
        if (permission != null && results.values.none { it }) {
            val activity = context.findActivity()
            val shouldExplain = activity != null && permission.manifestPermissions.any {
                activity.shouldShowRequestPermissionRationale(it)
            }
            if (!shouldExplain) {
                context.safeStart(inspector.appDetailsIntent())
            }
        }
        pending = null
    }

    val states = remember(revision) {
        AssistantPermissions.all.associateWith { inspector.state(it) }
    }
    val grantedCount = states.count { it.value.isSettled }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("SYSTEM ACCESS", style = AssistantLabel, color = AssistantColors.Admin)
            Text(
                text = "$grantedCount / ${AssistantPermissions.all.size}",
                style = AssistantLabel,
                color = if (grantedCount == AssistantPermissions.all.size) {
                    AssistantColors.Asset
                } else {
                    AssistantColors.Dim
                },
            )
        }
        AssistantRule(Modifier.fillMaxWidth().height(1.dp))

        for (tier in PermissionTier.entries) {
            val inTier = AssistantPermissions.all.filter { it.tier == tier }
            if (inTier.isEmpty()) continue
            Text(
                text = tier.heading(),
                style = AssistantLabel,
                color = AssistantColors.Ghost,
                modifier = Modifier.padding(top = 6.dp),
            )
            for (permission in inTier) {
                AccessRow(
                    permission = permission,
                    state = states.getValue(permission),
                    onGrant = {
                        AssistantSounds.play(AssistantSounds.Cue.TICK, volume = 0.3f)
                        when (permission.mechanism) {
                            GrantMechanism.RUNTIME_DIALOG -> {
                                pending = permission
                                runtimeLauncher.launch(permission.manifestPermissions.toTypedArray())
                            }

                            GrantMechanism.SPECIAL_SETTINGS -> {
                                val intent = inspector.settingsIntent(permission)
                                if (intent == null || !context.safeStart(intent)) {
                                    context.safeStart(inspector.appDetailsIntent())
                                }
                            }

                            // Nothing to grant ahead of time; the system asks at capture time.
                            GrantMechanism.PER_USE_CONSENT -> Unit
                        }
                    },
                )
            }
        }

        RestrictedSettingsNote(
            visible = states[AssistantPermissions.byId(AssistantPermissions.ACCESSIBILITY)] !=
                PermissionState.GRANTED,
        )
    }
}

private fun PermissionTier.heading(): String = when (this) {
    PermissionTier.CORE -> "REQUIRED"
    PermissionTier.CONTROL -> "DEVICE CONTROL"
    PermissionTier.DATA -> "SENSORS AND DATA"
}

@Composable
private fun AccessRow(
    permission: AssistantPermission,
    state: PermissionState,
    onGrant: () -> Unit,
) {
    val tone = state.tone()
    val actionable = state != PermissionState.GRANTED &&
        permission.mechanism != GrantMechanism.PER_USE_CONSENT
    val progress = rememberSnapProgress(locked = state == PermissionState.GRANTED)

    TrackingBox(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (actionable) Modifier.clickable(onClick = onGrant) else Modifier),
        color = tone,
        progress = progress,
        cornerLength = 10.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(tone),
                    )
                    Text(
                        text = "  ${permission.title.uppercase()}",
                        style = AssistantLabel,
                        color = AssistantColors.Bone,
                    )
                }
                Text(text = state.caption(), style = AssistantLabel, color = tone)
            }
            Text(
                text = permission.rationale,
                style = AssistantReadout,
                color = AssistantColors.Dim,
            )
            if (actionable) {
                Text(
                    text = when (permission.mechanism) {
                        GrantMechanism.SPECIAL_SETTINGS -> "TAP TO OPEN SETTINGS →"
                        else -> "TAP TO GRANT →"
                    },
                    style = AssistantLabel,
                    color = AssistantColors.Admin,
                )
            }
        }
    }
}

/**
 * Android refuses to let a sideloaded app turn on its own accessibility service until
 * the user has explicitly allowed restricted settings for it. Sending someone to the
 * accessibility screen without saying this leaves them looking at a toggle that does
 * nothing, which reads as a broken app rather than a deliberate safety measure.
 */
@Composable
private fun RestrictedSettingsNote(visible: Boolean) {
    if (!visible) return
    val context = LocalContext.current
    val inspector = remember(context) { PermissionInspector(context) }
    TrackingBox(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { context.safeStart(inspector.appDetailsIntent()) },
        color = AssistantColors.Admin,
        cornerLength = 10.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("IF THE TOGGLE IS GREYED OUT", style = AssistantLabel, color = AssistantColors.Admin)
            Text(
                text = "Android blocks sideloaded apps from enabling accessibility until you " +
                    "allow it. Open App info, tap the three dots at the top right, and choose " +
                    "\"Allow restricted settings\". Then come back and try again.",
                style = AssistantReadout,
                color = AssistantColors.Dim,
            )
            Text("TAP TO OPEN APP INFO →", style = AssistantLabel, color = AssistantColors.Admin)
        }
    }
}

private fun PermissionState.tone(): Color = when (this) {
    PermissionState.GRANTED -> AssistantColors.Asset
    PermissionState.NOT_GRANTED -> AssistantColors.Dim
    PermissionState.PERMANENTLY_DENIED -> AssistantColors.Relevant
    PermissionState.ASK_EVERY_TIME -> AssistantColors.Irrelevant
    PermissionState.ENABLED_NOT_RUNNING -> AssistantColors.Relevant
}

private fun PermissionState.caption(): String = when (this) {
    PermissionState.GRANTED -> "GRANTED"

    PermissionState.NOT_GRANTED -> "NOT GRANTED"

    PermissionState.PERMANENTLY_DENIED -> "BLOCKED"

    PermissionState.ASK_EVERY_TIME -> "ASKED EACH USE"

    // On in Settings, not started by Android. Naming it is the whole point: the switch
    // the user would go and check is already on.
    PermissionState.ENABLED_NOT_RUNNING -> "ON BUT NOT RUNNING"
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** OEM builds move these screens around; a missing one must not take the app down. */
private fun Context.safeStart(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    android.util.Log.w("Assistant", "no activity for ${intent.action}", e)
    false
}
