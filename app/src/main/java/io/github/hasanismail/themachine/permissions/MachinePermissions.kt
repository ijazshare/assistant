/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.permissions

import android.Manifest

/**
 * How the platform hands out a given capability. This matters more than it looks:
 * the three mechanisms need three completely different pieces of UI, and treating a
 * special-access grant as if a dialog would appear is the usual way an onboarding
 * flow ends up looking broken.
 */
enum class GrantMechanism {
    /** A normal runtime permission dialog, via the activity result API. */
    RUNTIME_DIALOG,

    /** No dialog exists — the user has to be sent to a Settings screen and come back. */
    SPECIAL_SETTINGS,

    /** Consent is asked for at the moment of use and cannot be held. */
    PER_USE_CONSENT,
}

/** Grouping used to order onboarding, roughly by how much the app needs it. */
enum class PermissionTier {
    /** Without these the assistant cannot do its core job at all. */
    CORE,

    /** Lets the assistant see and act on the phone. */
    CONTROL,

    /** Sensors and personal data. Genuinely optional; the app degrades, it does not break. */
    DATA,
}

/**
 * One capability the app asks for, with everything the onboarding UI needs to explain
 * and request it.
 *
 * The rationale strings are written to be shown to a person, not to satisfy a policy
 * checkbox — this app reads messages and controls the phone, and someone granting that
 * deserves a straight answer about why.
 */
data class MachinePermission(
    val id: String,
    val title: String,
    val rationale: String,
    val tier: PermissionTier,
    val mechanism: GrantMechanism,
    /** Android permission constants requested together; empty for special access. */
    val manifestPermissions: List<String> = emptyList(),
    /** True when the app still works, just with less, if this is refused. */
    val optional: Boolean = true,
) {
    val isRuntime: Boolean get() = mechanism == GrantMechanism.RUNTIME_DIALOG
}

/**
 * The catalog. Ordered as onboarding presents it: the things the assistant cannot work
 * without first, then the ones that let it act, then the personal data.
 */
object MachinePermissions {

    const val MICROPHONE = "microphone"
    const val NOTIFICATIONS = "notifications"
    const val EXACT_ALARMS = "exact_alarms"
    const val ACCESSIBILITY = "accessibility"
    const val OVERLAY = "overlay"
    const val NOTIFICATION_ACCESS = "notification_access"
    const val ASSISTANT_ROLE = "assistant_role"
    const val SCREEN_CAPTURE = "screen_capture"
    const val CAMERA = "camera"
    const val LOCATION = "location"
    const val CONTACTS = "contacts"
    const val MESSAGES = "messages"

    val all: List<MachinePermission> = listOf(
        MachinePermission(
            id = MICROPHONE,
            title = "Microphone",
            rationale = "To hear your commands. Audio is transcribed on the device and is never " +
                "written to disk or sent anywhere.",
            tier = PermissionTier.CORE,
            mechanism = GrantMechanism.RUNTIME_DIALOG,
            manifestPermissions = listOf(Manifest.permission.RECORD_AUDIO),
            optional = false,
        ),
        MachinePermission(
            id = NOTIFICATIONS,
            title = "Show notifications",
            rationale = "To fire your reminders and show model downloads in progress.",
            tier = PermissionTier.CORE,
            mechanism = GrantMechanism.RUNTIME_DIALOG,
            manifestPermissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
            optional = false,
        ),
        MachinePermission(
            id = EXACT_ALARMS,
            title = "Exact alarms",
            rationale = "So a reminder fires at the minute you asked for, instead of whenever the " +
                "system next feels like waking the app.",
            tier = PermissionTier.CORE,
            mechanism = GrantMechanism.SPECIAL_SETTINGS,
            optional = false,
        ),
        MachinePermission(
            id = ASSISTANT_ROLE,
            title = "Default assistant",
            rationale = "So holding the side button wakes The Machine instead of Google Assistant.",
            tier = PermissionTier.CORE,
            mechanism = GrantMechanism.SPECIAL_SETTINGS,
            optional = false,
        ),
        MachinePermission(
            id = ACCESSIBILITY,
            title = "Accessibility service",
            rationale = "How the assistant actually presses things for you: opening apps, tapping, " +
                "scrolling, going back. It can read what is on screen to find what to tap. " +
                "Nothing it reads leaves the device.",
            tier = PermissionTier.CONTROL,
            mechanism = GrantMechanism.SPECIAL_SETTINGS,
        ),
        MachinePermission(
            id = OVERLAY,
            title = "Display over other apps",
            rationale = "So the assistant can appear on top of whatever you are doing, and stay " +
                "visible while it acts on another app.",
            tier = PermissionTier.CONTROL,
            mechanism = GrantMechanism.SPECIAL_SETTINGS,
        ),
        MachinePermission(
            id = NOTIFICATION_ACCESS,
            title = "Notification access",
            rationale = "To read notifications so you can ask what you missed.",
            tier = PermissionTier.CONTROL,
            mechanism = GrantMechanism.SPECIAL_SETTINGS,
        ),
        MachinePermission(
            id = SCREEN_CAPTURE,
            title = "Screen capture",
            rationale = "To look at the current screen when you ask about it. Android asks for " +
                "this every single time capture starts — that is the system's dialog, not ours, " +
                "and it cannot be granted permanently.",
            tier = PermissionTier.CONTROL,
            mechanism = GrantMechanism.PER_USE_CONSENT,
        ),
        MachinePermission(
            id = CAMERA,
            title = "Camera",
            rationale = "To look at something when you ask about it.",
            tier = PermissionTier.DATA,
            mechanism = GrantMechanism.RUNTIME_DIALOG,
            manifestPermissions = listOf(Manifest.permission.CAMERA),
        ),
        MachinePermission(
            id = LOCATION,
            title = "Location",
            rationale = "For commands that depend on where you are.",
            tier = PermissionTier.DATA,
            mechanism = GrantMechanism.RUNTIME_DIALOG,
            manifestPermissions = listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        ),
        MachinePermission(
            id = CONTACTS,
            title = "Contacts",
            rationale = "So \"remind me to call Osman\" can match a name you actually know.",
            tier = PermissionTier.DATA,
            mechanism = GrantMechanism.RUNTIME_DIALOG,
            manifestPermissions = listOf(Manifest.permission.READ_CONTACTS),
        ),
        MachinePermission(
            id = MESSAGES,
            title = "Messages and calls",
            rationale = "To answer questions about your messages and missed calls. Android treats " +
                "these as restricted: on a sideloaded build the usual dialog may never appear, in " +
                "which case they have to be switched on in Settings by hand.",
            tier = PermissionTier.DATA,
            mechanism = GrantMechanism.RUNTIME_DIALOG,
            manifestPermissions = listOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_CALL_LOG,
            ),
        ),
    )

    fun byId(id: String): MachinePermission =
        all.firstOrNull { it.id == id } ?: error("Unknown permission id: $id")

    /** Everything that must be granted before the voice pipeline can run at all. */
    val required: List<MachinePermission> = all.filterNot { it.optional }
}
