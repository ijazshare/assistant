/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import io.github.hasanismail.themachine.R
import io.github.hasanismail.themachine.ui.MainActivity

/**
 * Shows a reminder when its alarm comes due, and handles the two things a person can do
 * with it from the notification: tick it off, or push it back ten minutes.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val task = intent.getStringExtra(EXTRA_TASK) ?: ""
        when (intent.action) {
            ACTION_DONE -> offMainThread {
                ReminderStore(context).complete(id)
                NotificationManagerCompat.from(context).cancel(id.hashCode())
            }

            ACTION_SNOOZE -> offMainThread {
                val due = ReminderStore(context).snooze(id, task)
                NotificationManagerCompat.from(context).cancel(id.hashCode())
                Log.i(TAG, "reminder snoozed to $due")
            }

            else -> show(context, id, task)
        }
    }

    /**
     * File rewrites belong off the main thread, and a receiver is torn down as soon as
     * onReceive returns unless it says otherwise.
     */
    private fun offMainThread(work: () -> Unit) {
        val pending = goAsync()
        Thread {
            try {
                work()
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun show(context: Context, id: String, task: String) {
        // Blocked notifications are dropped by the platform without a word; the store
        // warns at creation time, and this is the second chance to leave a trace.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "reminder due but notifications are off: $task")
            return
        }
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Reminder")
            .setContentText(task)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setStyle(NotificationCompat.BigTextStyle().bigText(task))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .addAction(0, "Done", action(context, id, task, ACTION_DONE, SALT_DONE))
            .addAction(0, "Snooze 10 min", action(context, id, task, ACTION_SNOOZE, SALT_SNOOZE))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }

    /** A distinct PendingIntent per button: same receiver, different action and data. */
    private fun action(context: Context, id: String, task: String, action: String, salt: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.hashCode() * SALT_STRIDE + salt,
            Intent(context, ReminderReceiver::class.java).apply {
                this.action = action
                data = Uri.parse("themachine://task/$id/$salt")
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TASK, task)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires when something you asked to be reminded of comes due."
            },
        )
    }

    companion object {
        private const val TAG = "TheMachine"
        const val EXTRA_ID = "id"
        const val EXTRA_TASK = "task"
        const val ACTION_FIRE = "io.github.hasanismail.themachine.REMINDER_FIRE"
        const val ACTION_DONE = "io.github.hasanismail.themachine.REMINDER_DONE"
        const val ACTION_SNOOZE = "io.github.hasanismail.themachine.REMINDER_SNOOZE"
        const val CHANNEL_ID = "reminders"
        private const val SALT_STRIDE = 31
        private const val SALT_DONE = 1
        private const val SALT_SNOOZE = 2
    }
}
