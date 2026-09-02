/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import io.github.hasanismail.themachine.tools.ReminderStore

/**
 * Application entry point. Does almost nothing: the voice pipeline's heavy pieces
 * (Whisper, llama, Piper) are loaded per-session and freed on session end.
 *
 * The one job it has is re-arming reminders. A force-stop from App info clears the
 * app's alarms exactly as a reboot does, and no receiver is told about it; the next
 * time the process comes up is the first chance to put them back.
 */
@HiltAndroidApp
class TheMachineApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread {
            runCatching { ReminderStore(this).rescheduleAll() }
                .onFailure { Log.e("TheMachine", "reminder reschedule failed", it) }
        }.apply { isDaemon = true }.start()
    }
}
