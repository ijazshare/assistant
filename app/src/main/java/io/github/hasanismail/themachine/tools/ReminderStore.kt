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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import io.github.hasanismail.themachine.context.MachineFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tasks the assistant has been asked to remember, and the alarms that fire them.
 *
 * Stored as Markdown rather than in a database, because the same file is read back
 * into the model's prompt as context and is meant to be readable and editable by the
 * user directly. A row in a Room table would be neither.
 */
class ReminderStore(private val context: Context) {

    private val files = MachineFiles(context)

    /**
     * Records a task, and schedules an exact alarm if a time was given.
     *
     * A time in the past rolls forward rather than firing immediately: someone saying
     * "remind me at 7" at 9pm means tomorrow morning, and an alarm that goes off the
     * instant they finish speaking is the least useful possible reading.
     */
    suspend fun create(
        task: String,
        hour: Int?,
        minute: Int,
        tomorrow: Boolean,
    ): ToolResult = withContext(Dispatchers.IO) {
        if (hour == null) {
            files.appendTask(task, due = null)
            return@withContext ToolResult.ok("I will remember: $task.")
        }
        if (hour !in 0..HOUR_MAX || minute !in 0..MINUTE_MAX) {
            return@withContext ToolResult.failed("That is not a time I can use.")
        }

        val now = LocalDateTime.now()
        var due = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (tomorrow || due.isBefore(now)) due = due.plusDays(1)

        files.appendTask(task, due)

        val manager = context.getSystemService<AlarmManager>()
            ?: return@withContext ToolResult.failed("I cannot reach the alarm service.")
        if (!manager.canScheduleExactAlarms()) {
            return@withContext ToolResult.ok(
                "I have written that down, but I cannot set an exact alarm yet.",
                "Grant Exact alarms under System access.",
            )
        }

        val whenMillis = due.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TASK, task)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            whenMillis.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return@withContext try {
            // AllowWhileIdle so Doze cannot quietly defer a reminder the user set
            // deliberately — that is the entire point of an exact alarm.
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending)
            ToolResult.ok("I will remind you to $task at ${due.format(SPOKEN_TIME)}.")
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused", e)
            ToolResult.ok("I have written that down, but I could not set the alarm.")
        }
    }

    private companion object {
        const val TAG = "TheMachine"
        const val HOUR_MAX = 23
        const val MINUTE_MAX = 59
        val SPOKEN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    }
}
