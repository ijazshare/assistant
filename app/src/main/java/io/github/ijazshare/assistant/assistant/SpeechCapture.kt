/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.ijazshare.assistant.audio.AssistantSounds
import io.github.ijazshare.assistant.audio.CaptureEvent
import io.github.ijazshare.assistant.audio.Endpointer
import io.github.ijazshare.assistant.audio.SileroVoiceModel
import io.github.ijazshare.assistant.audio.VoiceCheckLayer
import io.github.ijazshare.assistant.models.ModelArchive
import io.github.ijazshare.assistant.models.ModelRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Assistant"

/**
 * Unpacks and loads the voice, if one is installed. Never fails the session: an
 * assistant that cannot speak is diminished, not broken, and its reply is on screen.
 */
internal fun VoiceSession.prepareVoice() {
    val asset = installed(ModelRole.TTS) ?: return
    if (piper.isLoaded) return
    scope.launch {
        val unpacked = withContext(Dispatchers.IO) {
            ModelArchive.unpack(storage.target(asset), storage.extractedDir(asset))
        }
        if (unpacked) piper.load(storage.extractedDir(asset))
    }
}

/**
 * Loads the voice check's model, if the optional download is installed. Never fails the
 * session: without it the microphone closes on the energy endpointer alone, as it always has.
 *
 * [SileroVoiceModel.open] hashes the file before it is handed to native code: a model with
 * another window size ends the process.
 */
internal fun VoiceSession.prepareVoiceCheck() {
    val asset = installed(ModelRole.VAD) ?: return
    voiceModel.prepare {
        // LinkageError as well: a native library that will not load is an Error, not an Exception.
        try {
            SileroVoiceModel.open(storage.target(asset))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "voice check: ${asset.fileName} is not the expected model or would not load", e)
            null
        } catch (e: LinkageError) {
            Log.w(TAG, "voice check: native library missing", e)
            null
        }
    }
}

/**
 * Lint cannot see that [start] refuses to get here without RECORD_AUDIO; the check
 * it wants has already happened one frame up.
 */
@android.annotation.SuppressLint("MissingPermission")
internal suspend fun VoiceSession.listen() {
    _state.value = SessionState.Listening(level = 0f, heardSpeech = false)
    AssistantSounds.capturing = true
    // The microphone needs a foreground state, which the service provides. Scoped to the
    // capture and dropped in the finally the instant it ends — on an endpoint or a
    // dismissal alike — so the "listening" notification never outlives the listening.
    MicForegroundService.start(context)
    // The endpointer ignores the first few frames, which are the microphone opening
    // rather than anything in the room. The recorder makes one for each source it opens.
    // The voice check, if its model is installed, sits on top of each and starts afresh with it,
    // the model's memory included. Each gets its own handle on the model, so a capture that was
    // slow to stop cannot feed its audio into the next one's memory.
    val model = voiceModel.current()
    val newEndpointer = {
        VoiceCheckLayer(Endpointer(leadInMillis = Endpointer.MIC_SETTLE_MILLIS)::accept, model?.newCapture())
    }
    var finished: CaptureEvent.Finished? = null
    try {
        recorder.capture(newEndpointer).collect { event ->
            when (event) {
                is CaptureEvent.Level -> updateListening { it.copy(level = event.amplitude) }
                CaptureEvent.SpeechStarted -> updateListening { it.copy(heardSpeech = true) }
                is CaptureEvent.Snapshot -> transcribePartial(event.samples)
                is CaptureEvent.Failed -> fail(event.reason)
                is CaptureEvent.Finished -> finished = event
            }
        }
    } finally {
        MicForegroundService.stop(context)
    }

    // Handled after the flow ends rather than inside it, and launched rather than
    // awaited. Running the pipeline within the collector kept this job alive through
    // transcription, the language model and the spoken reply, so the next summon —
    // which has to join this job before it may open the microphone — waited for all
    // of it. Nine seconds of an overlay that is up, looks ready, and is not
    // listening. This job's one remaining duty is the microphone.
    val done = finished ?: return
    processing?.cancel()
    processing = scope.launch { handle(done) }
}

/**
 * Transcribes what has been heard so far, so the words appear as they are spoken.
 *
 * Dropped silently if the transcriber is busy or the state has moved on: a partial
 * is worth showing only while it is still current, and never worth delaying the
 * microphone for.
 */
internal fun VoiceSession.transcribePartial(samples: FloatArray) {
    if (!whisper.isLoaded || samples.isEmpty()) return
    if (!transcriber.tryLock()) return
    scope.launch {
        try {
            val heard = whisper.transcribe(samples)
            if (heard.text.isNotBlank()) {
                updateListening { it.copy(partial = heard.text) }
            }
        } finally {
            transcriber.unlock()
        }
    }
}

internal fun VoiceSession.hasMicrophonePermission(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Takes audio focus so other apps' media pauses while the assistant is active — both to
 * keep the microphone clean and so the reply is heard rather than talked over. Transient
 * exclusive, so others pause rather than duck, and it is given straight back when done.
 */
internal fun VoiceSession.requestAudioFocus() {
    val manager = audioManager ?: return
    if (audioFocus != null) return
    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()
    manager.requestAudioFocus(request)
    audioFocus = request
}

internal fun VoiceSession.abandonAudioFocus() {
    val manager = audioManager ?: return
    audioFocus?.let { manager.abandonAudioFocusRequest(it) }
    audioFocus = null
}
