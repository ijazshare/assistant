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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.IOException

/**
 * Re-arms every reminder after a reboot.
 *
 * Alarms are held in memory by the system and are gone when it restarts; the tasks file
 * is not. Nothing else runs here — no service, no model — because a boot receiver has
 * seconds, and this needs one.
 */
class ReminderBootReceiver : BroadcastReceiver() {

    private companion object {
        /**
         * A reminder is stored as a local time and armed as an instant, so moving the
         * clock moves the reminder: flying two timezones east made a seven o'clock
         * reminder fire at five.
         */
        val REBUILD_ON = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in REBUILD_ON) return
        val pending = goAsync()
        Thread {
            try {
                ReminderStore(context).rescheduleAll()
            } catch (e: IOException) {
                // An unreadable tasks file at boot must not become a crash on every boot.
                Log.e("TheMachine", "reminder reschedule failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
