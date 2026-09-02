/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Read access to the notification shade, so "what did I miss" can be answered.
 *
 * Nothing is stored. The service holds no history of its own: when a command needs the
 * current notifications it calls [snapshot], which asks the system for what is on the
 * shade right now. Posted and removed callbacks are not overridden precisely so that
 * this cannot quietly accumulate a log of everything that has ever arrived.
 */
class MachineNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "TheMachine"

        @Volatile
        private var instance: MachineNotificationListener? = null

        /** The live service, or null when notification access has not been granted. */
        fun connected(): MachineNotificationListener? = instance
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.i(TAG, "notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** What is on the shade right now, newest first. Read on demand, never retained. */
    fun snapshot(limit: Int = 30): List<NotificationSummary> = runCatching {
        activeNotifications
            .orEmpty()
            .sortedByDescending { it.postTime }
            .take(limit)
            .map { it.toSummary() }
    }.getOrElse {
        // getActiveNotifications throws if the listener is not currently bound.
        Log.w(TAG, "could not read notifications: ${it.message}")
        emptyList()
    }

    private fun StatusBarNotification.toSummary(): NotificationSummary {
        val extras = notification.extras
        return NotificationSummary(
            packageName = packageName,
            title = extras.getCharSequence("android.title")?.toString().orEmpty(),
            text = extras.getCharSequence("android.text")?.toString().orEmpty(),
            postedAtMillis = postTime,
            ongoing = notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0,
        )
    }
}

/** A notification reduced to the parts a spoken answer would use. */
data class NotificationSummary(
    val packageName: String,
    val title: String,
    val text: String,
    val postedAtMillis: Long,
    val ongoing: Boolean,
)
