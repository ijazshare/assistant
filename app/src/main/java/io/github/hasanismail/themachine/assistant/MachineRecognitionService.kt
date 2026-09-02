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

import android.speech.RecognitionService

/**
 * A recognition service is mandatory scaffolding, not an optional extra: the
 * android:recognitionService attribute of <voice-interaction-service> is required, and
 * pointing it at a class that does not exist stops the whole app from being offered as
 * an assistant.
 *
 * The Machine does not recognise speech through this API — SpeechRecognizer is a
 * system-mediated, usually cloud-backed path, which is the opposite of the point. All
 * transcription happens in-process through Whisper. So these callbacks report an error
 * rather than pretending: any third-party app that binds here is told plainly that this
 * recogniser will not serve it, instead of being left waiting.
 */
class MachineRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: android.content.Intent?, listener: Callback?) {
        listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
