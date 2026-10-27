/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import io.github.ijazshare.assistant.R
import io.github.ijazshare.assistant.context.AssistantFiles
import io.github.ijazshare.assistant.ui.MainActivity
import java.io.IOException

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

            ACTION_DISMISS -> offMainThread { AssistantFiles(context).markSeen(id) }

            else -> {
                // Still showing means this is the copy a cold start armed again five seconds
                // out: AssistantApplication re-arms overdue reminders before this receiver
                // has marked the reminder delivered. Shown, it alerted twice; marked, it
                // dropped the flag a reboot needs.
                val showing = context.getSystemService<NotificationManager>()
                    ?.activeNotifications?.any { it.id == id.hashCode() } == true
                if (showing) return
                // Recorded as delivered before it is shown, so later process starts do not
                // find it overdue and arm it all over again.
                offMainThread { AssistantFiles(context).markDelivered(id) }
                show(context, id, task)
            }
        }
    }

    private fun show(context: Context, id: String, task: String) {
        // Blocked notifications are dropped by the platform without a word; the store
        // warns at creation time, and this is the second chance to leave a trace.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "reminder due but notifications are off: $task")
            return
        }
        ensureChannel(context)

        // Opening it counts as seen, like a swipe: the activity clears the reboot flag.
        val open = PendingIntent.getActivity(
            context,
            id.hashCode() * SALT_STRIDE + SALT_OPEN,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_SEEN, id),
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
            // Swiped away counts as seen; anything else left unanswered is shown again after a reboot.
            .setDeleteIntent(action(context, id, task, ACTION_DISMISS, SALT_DISMISS))
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
                data = "assistant://task/$id/$salt".toUri()
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
        private const val TAG = "Assistant"
        const val EXTRA_ID = "id"
        const val EXTRA_TASK = "task"
        const val EXTRA_SEEN = "seen"
        const val ACTION_FIRE = "io.github.ijazshare.assistant.REMINDER_FIRE"
        const val ACTION_DONE = "io.github.ijazshare.assistant.REMINDER_DONE"
        const val ACTION_SNOOZE = "io.github.ijazshare.assistant.REMINDER_SNOOZE"
        const val ACTION_DISMISS = "io.github.ijazshare.assistant.REMINDER_DISMISS"
        const val CHANNEL_ID = "reminders"
        private const val SALT_STRIDE = 31
        private const val SALT_DONE = 1
        private const val SALT_SNOOZE = 2
        private const val SALT_DISMISS = 3
        private const val SALT_OPEN = 4
    }
}

/**
 * File rewrites belong off the main thread, and a receiver is torn down as soon as
 * onReceive returns unless it says otherwise.
 */
internal fun BroadcastReceiver.offMainThread(work: () -> Unit) {
    val pending = goAsync()
    Thread {
        try {
            work()
        } catch (e: IOException) {
            // A full disk while ticking a task off should lose the tick, not the
            // process: this runs on a bare thread, where an escape is fatal.
            Log.e("Assistant", "reminder action failed", e)
        } finally {
            pending.finish()
        }
    }.start()
}
