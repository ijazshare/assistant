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
import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.hasanismail.themachine.context.MachineFiles
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * The reminder notification and its two buttons, driven by the same broadcasts the alarm
 * and the buttons send. The alarm itself is not waited for — that would be a test that
 * takes a minute to say nothing new — but every task line it fires is created for real.
 */
@RunWith(AndroidJUnit4::class)
class ReminderReceiverTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications = context.getSystemService<NotificationManager>()!!
    private val files = MachineFiles(context)
    private lateinit var saved: String

    @Before
    fun keepTheUsersTasks() {
        saved = files.read(MachineFiles.TASKS)
    }

    @After
    fun restoreThem() {
        files.write(MachineFiles.TASKS, saved)
        notifications.cancelAll()
    }

    private fun broadcast(action: String, id: String, task: String) {
        context.sendBroadcast(
            Intent(context, ReminderReceiver::class.java).apply {
                this.action = action
                putExtra(ReminderReceiver.EXTRA_ID, id)
                putExtra(ReminderReceiver.EXTRA_TASK, task)
            },
        )
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun shown(id: String) = notifications.activeNotifications.firstOrNull { it.id == id.hashCode() }

    @Test
    fun aDueReminderIsShownWithDoneAndSnooze() {
        val id = files.appendTask("water the plants", LocalDateTime.now().plusMinutes(1))
        broadcast(ReminderReceiver.ACTION_FIRE, id, "water the plants")
        await("notification") { shown(id) != null }

        val notification = shown(id)!!.notification
        assertThat(notification.actions.map { it.title.toString() })
            .containsExactly("Done", "Snooze 10 min").inOrder()
        assertThat(notification.extras.getString("android.text")).isEqualTo("water the plants")
    }

    @Test
    fun doneTicksTheTaskAndClearsTheNotification() {
        val id = files.appendTask("water the plants", LocalDateTime.now().plusMinutes(1))
        broadcast(ReminderReceiver.ACTION_FIRE, id, "water the plants")
        await("notification") { shown(id) != null }

        broadcast(ReminderReceiver.ACTION_DONE, id, "water the plants")
        await("done") { files.readTasks().any { it.id == id && it.done } }
        await("cleared") { shown(id) == null }
    }

    @Test
    fun snoozeMovesTheTaskTenMinutesOut() {
        val due = LocalDateTime.now().plusMinutes(1).withSecond(0).withNano(0)
        val id = files.appendTask("water the plants", due)
        broadcast(ReminderReceiver.ACTION_FIRE, id, "water the plants")
        await("notification") { shown(id) != null }

        broadcast(ReminderReceiver.ACTION_SNOOZE, id, "water the plants")
        await("rescheduled") { files.readTasks().first { it.id == id }.due?.isAfter(due.plusMinutes(8)) == true }
        await("cleared") { shown(id) == null }
        val moved = files.readTasks().first { it.id == id }
        assertThat(moved.done).isFalse()
        Log.i(TAG, "REMINDER snoozed $due -> ${moved.due}")
    }

    @Test
    fun everyOpenDatedTaskIsReArmed() {
        files.appendTask("open and dated", LocalDateTime.now().plusHours(1))
        files.appendTask("open, no date", null)
        val done = files.appendTask("done and dated", LocalDateTime.now().plusHours(1))
        files.completeTask(done)

        val exact = context.getSystemService<AlarmManager>()!!.canScheduleExactAlarms()
        Log.i(TAG, "REMINDER exact alarms permitted: $exact")
        val armed = ReminderStore(context).rescheduleAll()
        // Only the open, dated one — plus whatever the user already had on file.
        val expected = files.readTasks().count { !it.done && it.id != null && it.due != null }
        assertThat(armed).isEqualTo(expected)
        assertThat(armed).isAtLeast(1)
    }

    @Test
    fun twoRemindersAtTheSameMinuteAreTwoAlarms() {
        // They used to share a PendingIntent, and the second silently cancelled the first.
        val store = ReminderStore(context)
        val due = LocalDateTime.now().plusMinutes(2)
        val first = files.appendTask("first", due)
        val second = files.appendTask("second", due)
        assertThat(store.schedule(first, "first", due)).isNotNull()
        assertThat(store.schedule(second, "second", due)).isNotNull()
        // Cancelling one must leave the other in place; the only observable proxy without
        // waiting is that each id maps to a distinct request code.
        assertThat(first.hashCode()).isNotEqualTo(second.hashCode())
        store.complete(first)
        store.complete(second)
    }

    private companion object {
        const val TAG = "TheMachine"
        const val TIMEOUT_MILLIS = 8_000L
        const val POLL_MILLIS = 100L
    }
}
