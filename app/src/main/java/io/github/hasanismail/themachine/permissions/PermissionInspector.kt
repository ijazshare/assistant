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

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import io.github.hasanismail.themachine.services.MachineAccessibilityService
import io.github.hasanismail.themachine.services.MachineNotificationListener

/** What the system currently thinks about one capability. */
enum class PermissionState {
    GRANTED,

    /** Never asked, or asked and dismissed — asking again will still show a dialog. */
    NOT_GRANTED,

    /**
     * Refused hard enough that the platform will no longer show a dialog. The only
     * route left is app settings, so the UI must say so rather than re-requesting
     * into a no-op.
     */
    PERMANENTLY_DENIED,

    /** Cannot be held in advance; consent happens at the moment of use. */
    ASK_EVERY_TIME,

    /**
     * Switched on in Settings, but not running.
     *
     * Android leaves an accessibility service listed as enabled after its process is
     * replaced — an app update, or a force-stop — and does not always bind it again.
     * The switch is on, the service is dead, and every screen command fails while the
     * settings screen insists the permission was granted. Saying GRANTED here is the
     * difference between a user who knows to toggle it and one who thinks the app is
     * broken.
     */
    ENABLED_NOT_RUNNING,
    ;

    /**
     * True when there is nothing further the user can do about this one.
     *
     * ASK_EVERY_TIME counts: MediaProjection has no persistent grant by design, so a
     * progress counter that treats it as outstanding can never reach the total and
     * reads as a bug rather than as the platform behaving correctly.
     */
    val isSettled: Boolean
        get() = this == GRANTED || this == ASK_EVERY_TIME
}

/**
 * Reads the current grant state of everything in [MachinePermissions], and knows where
 * to send the user for the ones that have no dialog.
 */
class PermissionInspector(private val context: Context) {

    fun state(permission: MachinePermission): PermissionState = when (permission.id) {
        MachinePermissions.EXACT_ALARMS -> if (canScheduleExactAlarms()) {
            PermissionState.GRANTED
        } else {
            PermissionState.NOT_GRANTED
        }

        MachinePermissions.ACCESSIBILITY -> accessibilityState()

        MachinePermissions.OVERLAY -> if (Settings.canDrawOverlays(context)) {
            PermissionState.GRANTED
        } else {
            PermissionState.NOT_GRANTED
        }

        MachinePermissions.NOTIFICATION_ACCESS -> if (isNotificationListenerEnabled()) {
            PermissionState.GRANTED
        } else {
            PermissionState.NOT_GRANTED
        }

        MachinePermissions.ASSISTANT_ROLE -> if (isDefaultAssistant()) {
            PermissionState.GRANTED
        } else {
            PermissionState.NOT_GRANTED
        }

        // MediaProjection deliberately has no persistent grant to inspect.
        MachinePermissions.SCREEN_CAPTURE -> PermissionState.ASK_EVERY_TIME

        else -> runtimeState(permission)
    }

    /**
     * A runtime group counts as granted only when every permission in it is granted —
     * location asks for coarse and fine together, and messages for three at once.
     */
    private fun runtimeState(permission: MachinePermission): PermissionState {
        if (permission.manifestPermissions.isEmpty()) return PermissionState.NOT_GRANTED
        val allGranted = permission.manifestPermissions.all { granted(it) }
        if (!allGranted) return PermissionState.NOT_GRANTED
        // Being "granted" is not sufficient for the hard-restricted family — see below.
        return if (permission.manifestPermissions.all { appOpAllows(it) }) {
            PermissionState.GRANTED
        } else {
            PermissionState.PERMANENTLY_DENIED
        }
    }

    /**
     * The SMS and call-log permissions are *hard restricted*: the platform will happily
     * report them as granted while quietly setting their app op to `ignore`, in which
     * case every query returns an empty cursor instead of throwing. An app that only
     * checks `checkSelfPermission` therefore reports "granted" and then behaves as
     * though the user has no messages at all.
     *
     * Whether the restriction is lifted depends on the *installer*: installing over adb
     * exempts them (RESTRICTION_INSTALLER_EXEMPT, verified on the reference device),
     * while a plain file-manager install may not. So the op has to be checked too.
     */
    private fun appOpAllows(androidPermission: String): Boolean {
        val op = AppOpsManager.permissionToOp(androidPermission) ?: return true
        val appOps = context.getSystemService<AppOpsManager>() ?: return true
        val mode = appOps.unsafeCheckOpNoThrow(op, android.os.Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun granted(androidPermission: String): Boolean =
        ContextCompat.checkSelfPermission(context, androidPermission) ==
            PackageManager.PERMISSION_GRANTED

    fun canScheduleExactAlarms(): Boolean =
        context.getSystemService<AlarmManager>()?.canScheduleExactAlarms() ?: false

    /**
     * Reads the enabled-services list rather than asking the service about itself: the
     * service object only exists once the system has bound it, so asking it whether it
     * is enabled cannot answer "no".
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(context, MachineAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == expected
        }
    }

    /**
     * Enabled and running, enabled but dead, or off.
     *
     * Both halves are needed. The settings list is the only way to see that the user
     * said yes; the live instance is the only way to see that the system acted on it.
     */
    fun accessibilityState(): PermissionState = when {
        !isAccessibilityServiceEnabled() -> PermissionState.NOT_GRANTED
        MachineAccessibilityService.connected() != null -> PermissionState.GRANTED
        else -> PermissionState.ENABLED_NOT_RUNNING
    }

    fun isNotificationListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    fun isDefaultAssistant(): Boolean {
        val roleManager = context.getSystemService<RoleManager>() ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    /**
     * Where to send the user for a capability with no runtime dialog. Every one of these
     * is best-effort: OEM builds move these screens around, so callers should fall back
     * to app details if starting the intent throws.
     */
    fun settingsIntent(permission: MachinePermission): Intent? = when (permission.id) {
        MachinePermissions.EXACT_ALARMS ->
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri())

        MachinePermissions.ACCESSIBILITY ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        MachinePermissions.OVERLAY ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())

        MachinePermissions.NOTIFICATION_ACCESS ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        MachinePermissions.ASSISTANT_ROLE ->
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

        else -> null
    }

    /** App info — the fallback for a permanently denied runtime permission. */
    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    private fun packageUri(): Uri = Uri.fromParts("package", context.packageName, null)

    /** The notification listener component, for the per-service settings deep link. */
    fun notificationListenerComponent(): ComponentName =
        ComponentName(context, MachineNotificationListener::class.java)
}
