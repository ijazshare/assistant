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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.hasanismail.themachine.audio.CaptureEvent
import io.github.hasanismail.themachine.audio.MachineSounds
import io.github.hasanismail.themachine.audio.StopReason
import io.github.hasanismail.themachine.audio.VoiceRecorder
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.stt.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the session is in the listen → understand → act pipeline. */
sealed interface SessionState {
    data object Idle : SessionState
    data object Preparing : SessionState
    data class Listening(val level: Float, val heardSpeech: Boolean) : SessionState
    data object Transcribing : SessionState
    data class Heard(val transcript: String, val millis: Long, val realTimeFactor: Float) : SessionState
    data class Problem(val message: String, val actionable: String? = null) : SessionState
}

/**
 * Drives one spoken interaction.
 *
 * Owns the Whisper context for the life of the session rather than per utterance —
 * loading the model is the single largest cost in the pipeline, and paying it once per
 * summon instead of once per sentence is what keeps a follow-up question fast.
 */
class VoiceSession(private val context: Context, private val scope: CoroutineScope) {

    private val recorder = VoiceRecorder()
    private val whisper = WhisperEngine(context)
    private val storage = ModelStorage(context)
    private val registry = ModelRegistry(context)

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Begins listening. Everything that can go wrong before the microphone opens —
     * no permission, no model — is reported as a [SessionState.Problem] with something
     * the user can actually do about it, rather than a silent no-op.
     */
    fun start() {
        scope.launch {
            if (!hasMicrophonePermission()) {
                _state.value = SessionState.Problem(
                    "The Machine cannot hear you without microphone access.",
                    "Open the app and grant Microphone under System access.",
                )
                MachineSounds.play(MachineSounds.Cue.REJECT)
                return@launch
            }

            val model = installedSttModel()
            if (model == null) {
                _state.value = SessionState.Problem(
                    "No speech model is installed yet.",
                    "Open the app and download a model under Models.",
                )
                MachineSounds.play(MachineSounds.Cue.REJECT)
                return@launch
            }

            _state.value = SessionState.Preparing
            if (!whisper.isLoaded && !whisper.load(model)) {
                _state.value = SessionState.Problem("The speech model could not be loaded.")
                MachineSounds.play(MachineSounds.Cue.REJECT)
                return@launch
            }

            listen()
        }
    }

    private suspend fun listen() {
        _state.value = SessionState.Listening(level = 0f, heardSpeech = false)
        recorder.capture().collect { event ->
            when (event) {
                is CaptureEvent.Level -> {
                    val current = _state.value
                    if (current is SessionState.Listening) {
                        _state.value = current.copy(level = event.amplitude)
                    }
                }

                CaptureEvent.SpeechStarted -> {
                    val current = _state.value
                    if (current is SessionState.Listening) {
                        _state.value = current.copy(heardSpeech = true)
                    }
                }

                is CaptureEvent.Failed -> {
                    _state.value = SessionState.Problem(event.reason)
                    MachineSounds.play(MachineSounds.Cue.REJECT)
                }

                is CaptureEvent.Finished -> transcribe(event)
            }
        }
    }

    private suspend fun transcribe(event: CaptureEvent.Finished) {
        if (event.reason == StopReason.NO_SPEECH || event.samples.isEmpty()) {
            _state.value = SessionState.Problem("I did not hear anything.")
            MachineSounds.play(MachineSounds.Cue.REJECT)
            return
        }
        MachineSounds.play(MachineSounds.Cue.DISENGAGE, volume = 0.4f)
        _state.value = SessionState.Transcribing

        val result = whisper.transcribe(event.samples)
        Log.i(
            TAG,
            "transcribed ${event.samples.size} samples in ${result.durationMillis} ms " +
                "(rtf ${result.realTimeFactor}): \"${result.text}\"",
        )

        _state.value = if (result.text.isBlank()) {
            MachineSounds.play(MachineSounds.Cue.REJECT)
            SessionState.Problem("I heard something, but could not make out any words.")
        } else {
            MachineSounds.play(MachineSounds.Cue.CONFIRM, volume = 0.4f)
            SessionState.Heard(result.text, result.durationMillis, result.realTimeFactor)
        }
    }

    /**
     * Any installed model for the role will do — the user may have chosen base.en over
     * the default, and that is a working recogniser.
     */
    private fun installedSttModel() = registry.byRole(ModelRole.STT)
        .firstOrNull { storage.quickState(it) == ModelState.Ready }
        ?.let { storage.target(it) }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Frees the model. Called when the overlay goes away, not between utterances. */
    fun release() {
        whisper.unload()
        _state.value = SessionState.Idle
    }

    private companion object {
        const val TAG = "TheMachine"
    }
}
