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
import io.github.hasanismail.themachine.context.MachineFiles
import io.github.hasanismail.themachine.llm.LlamaEngine
import io.github.hasanismail.themachine.llm.PromptDialect
import io.github.hasanismail.themachine.models.ModelArchive
import io.github.hasanismail.themachine.models.ModelAsset
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.settings.MachineSettings
import io.github.hasanismail.themachine.stt.WhisperEngine
import io.github.hasanismail.themachine.tools.ContactLookup
import io.github.hasanismail.themachine.tools.MachineTools
import io.github.hasanismail.themachine.tools.ReminderStore
import io.github.hasanismail.themachine.tools.ToolExecutor
import io.github.hasanismail.themachine.tools.ToolResult
import io.github.hasanismail.themachine.tts.PiperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the session is in the listen, understand, act pipeline. */
sealed interface SessionState {
    data object Idle : SessionState
    data object Preparing : SessionState
    data class Listening(
        val level: Float,
        val heardSpeech: Boolean,
        /** What has been made out so far; grows while the user is still talking. */
        val partial: String = "",
    ) : SessionState
    data object Transcribing : SessionState
    data class Thinking(val transcript: String) : SessionState
    data class Done(val transcript: String, val tool: String, val result: ToolResult, val timing: Timing) :
        SessionState

    data class Problem(val message: String, val actionable: String? = null) : SessionState
}

/** Where the time went, so a regression against the latency budget is visible. */
data class Timing(val sttMillis: Long, val llmMillis: Long) {
    val totalMillis: Long get() = sttMillis + llmMillis
}

/**
 * Drives one spoken interaction end to end: capture, transcribe, choose a tool, run it.
 *
 * Both engines are loaded once per summon and freed when the overlay closes. Loading is
 * the dominant cost, so paying it per utterance would spend the whole latency budget
 * before any audio arrived.
 */
class VoiceSession(private val context: Context, private val scope: CoroutineScope) {

    private val recorder = VoiceRecorder()
    private val whisper = WhisperEngine(context)
    private val llama = LlamaEngine(context)
    private val storage = ModelStorage(context)
    private val registry = ModelRegistry(context)
    private val settings = MachineSettings(context)
    private val files = MachineFiles(context)
    private val executor = ToolExecutor(context, ReminderStore(context), ContactLookup(context))
    private val piper = PiperEngine()

    /**
     * Guards the transcriber, which is one engine and cannot run twice at once.
     *
     * Partial passes take the lock only if it is free, so a slow one is skipped rather
     * than queued; the final pass waits, because its result is the one that matters.
     */
    private val transcriber = Mutex()

    /** File name of whatever language model is loaded, for dialect selection. */
    private var loadedModelName: String = ""

    /** Whether this session has already written its prompt cache. */
    private var cacheWritten = false

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Begins listening. Anything that can go wrong before the microphone opens is
     * reported with something the user can actually do about it, not a silent no-op.
     */
    fun start() {
        scope.launch {
            if (!hasMicrophonePermission()) {
                fail(
                    "The Machine cannot hear you without microphone access.",
                    "Grant Microphone under System access.",
                )
                return@launch
            }
            val sttModel = installed(ModelRole.STT)
            if (sttModel == null) {
                fail("No speech model is installed yet.", "Download one under Models.")
                return@launch
            }

            _state.value = SessionState.Preparing
            if (!whisper.isLoaded && !whisper.load(storage.target(sttModel))) {
                fail("The speech model could not be loaded.")
                return@launch
            }
            // The voice is prepared in the background too. Unpacking it is a one-off
            // that costs a few seconds, and doing it here means the first reply is
            // spoken rather than silently skipped.
            prepareVoice()

            // The language model loads in the background while the user is still
            // speaking, so its load time overlaps the utterance instead of following it.
            installed(ModelRole.LLM)?.let { asset ->
                if (!llama.isLoaded) {
                    loadedModelName = asset.fileName
                    scope.launch { llama.load(storage.target(asset)) }
                }
            }
            listen()
        }
    }

    /**
     * Unpacks and loads the voice, if one is installed. Never fails the session: an
     * assistant that cannot speak is diminished, not broken, and its reply is on screen.
     */
    private fun prepareVoice() {
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
     * Lint cannot see that [start] refuses to get here without RECORD_AUDIO; the check
     * it wants has already happened one frame up.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun listen() {
        _state.value = SessionState.Listening(level = 0f, heardSpeech = false)
        recorder.capture().collect { event ->
            when (event) {
                is CaptureEvent.Level -> updateListening { it.copy(level = event.amplitude) }
                CaptureEvent.SpeechStarted -> updateListening { it.copy(heardSpeech = true) }
                is CaptureEvent.Snapshot -> transcribePartial(event.samples)
                is CaptureEvent.Failed -> fail(event.reason)
                is CaptureEvent.Finished -> handle(event)
            }
        }
    }

    /**
     * Transcribes what has been heard so far, so the words appear as they are spoken.
     *
     * Dropped silently if the transcriber is busy or the state has moved on: a partial
     * is worth showing only while it is still current, and never worth delaying the
     * microphone for.
     */
    private fun transcribePartial(samples: FloatArray) {
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

    private inline fun updateListening(block: (SessionState.Listening) -> SessionState) {
        val current = _state.value
        if (current is SessionState.Listening) _state.value = block(current)
    }

    private suspend fun handle(event: CaptureEvent.Finished) {
        if (event.reason == StopReason.NO_SPEECH || event.samples.isEmpty()) {
            fail("I did not hear anything.")
            return
        }
        MachineSounds.play(MachineSounds.Cue.DISENGAGE, volume = 0.4f)
        _state.value = SessionState.Transcribing

        val heard = transcriber.withLock { whisper.transcribe(event.samples) }
        if (heard.text.isBlank()) {
            fail("I heard something, but could not make out any words.")
            return
        }
        Log.i(TAG, "heard [${heard.text}] in ${heard.durationMillis} ms")
        _state.value = SessionState.Thinking(heard.text)
        act(heard.text, heard.durationMillis)
    }

    /**
     * Turns the transcript into a tool call and runs it.
     *
     * The grammar guarantees a valid call, so there is no retry loop: a null parse
     * would mean no grammar was applied, which is a programming error rather than a
     * runtime condition.
     */
    private suspend fun act(transcript: String, sttMillis: Long) {
        if (!llama.isLoaded && !loadLanguageModel()) return

        // The dialect owns the prompt, the grammar and the parser together, because a
        // fine-tuned model only behaves if all three match the form it was trained on.
        val dialect = PromptDialect.forModel(loadedModelName)
        val prompt = dialect.buildPrompt(
            transcript = transcript,
            tools = MachineTools.all,
            adminName = settings.adminNameNow(),
            userContext = files.contextForPrompt(),
        )
        val completion = llama.generate(prompt, dialect.grammar(MachineTools.all))
        Log.i(TAG, "model returned ${completion.text} in ${completion.millis} ms")

        val call = dialect.parse(completion.text)
        if (call == null) {
            fail("I could not work out what to do with that.")
            return
        }

        val result = executor.execute(call)
        MachineSounds.play(
            if (result.success) MachineSounds.Cue.CONFIRM else MachineSounds.Cue.REJECT,
            volume = 0.45f,
        )
        _state.value = SessionState.Done(
            transcript = transcript,
            tool = call.tool,
            result = result,
            timing = Timing(sttMillis, completion.millis),
        )
        Log.i(
            TAG,
            "session done: ${call.tool} -> ${result.spoken} " +
                "(stt $sttMillis ms, llm ${completion.millis} ms)",
        )

        // Written while the user is listening to the answer, not when the session ends:
        // by then the model is being freed, and a save racing an unload would lose to it.
        // Once per session is enough — the cached prefix is the same every time.
        if (!cacheWritten) {
            cacheWritten = true
            scope.launch { llama.saveState() }
        }

        // Said after the state is published, so the reply is on screen while it is being
        // spoken rather than after.
        piper.speak(result.spoken)
    }

    private suspend fun loadLanguageModel(): Boolean {
        val asset = installed(ModelRole.LLM)
        if (asset == null) {
            fail("I heard you, but no language model is installed.", "Download one under Models.")
            return false
        }
        loadedModelName = asset.fileName
        if (!llama.load(storage.target(asset))) {
            fail("The language model could not be loaded.")
            return false
        }
        return true
    }

    private fun fail(message: String, actionable: String? = null) {
        // Logged as well as shown. Every user-visible failure used to leave no trace at
        // all, which made a session that ended badly indistinguishable from one that
        // never ended, and both of them invisible to a test running over adb.
        Log.i(TAG, "session problem: $message${actionable?.let { " ($it)" } ?: ""}")
        _state.value = SessionState.Problem(message, actionable)
        MachineSounds.play(MachineSounds.Cue.REJECT)
        // Spoken too: the overlay sits over whatever the user was doing, and a problem
        // they have to read is one they may never notice.
        scope.launch { piper.speak(message) }
    }

    /**
     * The registry's default first, then anything else installed for the role.
     *
     * Order matters: without the preference, downloading an experimental model would
     * silently take over the pipeline just by being listed earlier.
     */
    private fun installed(role: ModelRole): ModelAsset? {
        val ready = registry.byRole(role).filter { storage.quickState(it) == ModelState.Ready }
        return ready.firstOrNull { it.isDefault } ?: ready.firstOrNull()
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Frees both models. Called when the overlay goes away, not between utterances. */
    fun release() {
        piper.release()
        whisper.unload()
        llama.unload()
        _state.value = SessionState.Idle
    }

    private companion object {
        const val TAG = "TheMachine"
    }
}
