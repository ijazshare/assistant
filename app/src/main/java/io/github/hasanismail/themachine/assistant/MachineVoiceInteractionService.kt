/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.assistant

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Declaring this is what makes The Machine appear under *Default digital assistant app*.
 *
 * Android builds the assistant picker by querying for services with the
 * android.service.voice.VoiceInteractionService intent filter that also carry the
 * android.voice_interaction metadata. Without this class the app is simply not a
 * candidate and never shows up in Settings, no matter what permissions it holds.
 *
 * The service itself does almost nothing: the platform keeps it alive as the assistant,
 * and hands actual interactions to the session service named in the metadata XML.
 */
class MachineVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i("TheMachine", "voice interaction service ready")
    }
}
