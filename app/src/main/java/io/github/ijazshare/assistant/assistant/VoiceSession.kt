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

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import io.github.ijazshare.assistant.audio.AssistantSounds
import io.github.ijazshare.assistant.audio.CaptureEvent
import io.github.ijazshare.assistant.audio.SileroVoiceModel
import io.github.ijazshare.assistant.audio.StopReason
import io.github.ijazshare.assistant.audio.VoiceRecorder
import io.github.ijazshare.assistant.context.AssistantFiles
import io.github.ijazshare.assistant.history.CaptureArchive
import io.github.ijazshare.assistant.history.QueryLog
import io.github.ijazshare.assistant.history.QueryRecord
import io.github.ijazshare.assistant.history.QuerySource
import io.github.ijazshare.assistant.history.Resolution
import io.github.ijazshare.assistant.llm.LlamaEngine
import io.github.ijazshare.assistant.llm.ModelRouter
import io.github.ijazshare.assistant.llm.PromptBuilder
import io.github.ijazshare.assistant.llm.ToolCallParser
import io.github.ijazshare.assistant.models.ModelAsset
import io.github.ijazshare.assistant.models.ModelRegistry
import io.github.ijazshare.assistant.models.ModelRole
import io.github.ijazshare.assistant.models.ModelState
import io.github.ijazshare.assistant.models.ModelStorage
import io.github.ijazshare.assistant.settings.AssistantSettings
import io.github.ijazshare.assistant.stt.ContactNames
import io.github.ijazshare.assistant.stt.JunkTranscript
import io.github.ijazshare.assistant.stt.WhisperEngine
import io.github.ijazshare.assistant.tools.AssistantTools
import io.github.ijazshare.assistant.tools.Contact
import io.github.ijazshare.assistant.tools.ContactLookup
import io.github.ijazshare.assistant.tools.ContactRecency
import io.github.ijazshare.assistant.tools.ReminderStore
import io.github.ijazshare.assistant.tools.ToolCall
import io.github.ijazshare.assistant.tools.ToolExecutor
import io.github.ijazshare.assistant.tools.ToolGrammar
import io.github.ijazshare.assistant.tools.ToolResult
import io.github.ijazshare.assistant.tts.PiperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

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
    data class Done(
        val transcript: String,
        val tool: String,
        val result: ToolResult,
        val timing: Timing,
        /** True if the command ran from the learned-phrase cache, with no model involved. */
        val fromCache: Boolean = false,
    ) : SessionState

    data class Problem(val message: String, val actionable: String? = null) : SessionState

    /**
     * A spoken name matched several contacts. The command is held and the user picks who it
     * was meant for, rather than the assistant guessing and texting the wrong person.
     */
    data class ChooseContact(val spokenName: String, val options: List<Contact>) : SessionState
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
// The session is one cohesive state machine — capture, transcribe, route, disambiguate,
// execute, speak — and its methods are small and sequential; splitting it by the function
// count would scatter one flow across files rather than clarify it.
@Suppress("TooManyFunctions")
class VoiceSession(internal val context: Context, internal val scope: CoroutineScope) {

    // Debug builds keep every capture for diagnosing end-of-speech (CaptureArchive), written
    // off the microphone's thread; release builds skip even the copy.
    private val captures = CaptureArchive(context)
    internal val recorder = VoiceRecorder(
        onCaptured = if (captures.enabled) {
            { c ->
                teardown.launch { captures.save(System.currentTimeMillis(), c) }
            }
        } else {
            null
        },
    )
    internal val whisper = WhisperEngine(context)
    private val llama = LlamaEngine(context)
    internal val storage = ModelStorage(context)
    private val registry = ModelRegistry(context)
    private val settings = AssistantSettings(context)
    private val files = AssistantFiles(context)

    /**
     * The owner's saved number, mirrored from settings so the lookup can read it without
     * blocking: "text me" goes here, never to whichever contact fuzzily matched "me".
     */
    private var ownNumber: String? = null
    internal val contacts = ContactLookup(context) { ownNumber }
    internal val recency = ContactRecency(context)
    private val executor = ToolExecutor(context, ReminderStore(context), contacts)

    init {
        scope.launch { settings.ownNumber.collect { ownNumber = it } }
    }

    internal val piper = PiperEngine()

    /**
     * The voice check's model, loaded once the first time it is wanted, if the optional download is
     * there. Empty runs the energy endpointer alone. A file that is not the verified model, or would
     * not load, is not retried.
     */
    internal val voiceModel = VoiceModelSlot<SileroVoiceModel> { it.release() }

    private val router = ModelRouter(context)

    /** Held while the assistant is active so other apps' media pauses, kept so it can be given back. */
    internal val audioManager = context.getSystemService(AudioManager::class.java)
    internal var audioFocus: AudioFocusRequest? = null
    private val cache = CommandCache.shared(File(context.getExternalFilesDir(null), CommandCache.FILE_NAME))
    private val history = QueryLog(context)

    /** Where the current command came from, for the record. */
    internal var source = QuerySource.VOICE

    /**
     * Counts every time the microphone was stopped on purpose, a dismissal, a switch to typing
     * or a typed command alike, so a reply that would reopen it can tell it was told not to.
     */
    internal var stops = 0

    /**
     * [stops] and the dismissals as they stood when the current command began, not when the model
     * was done with it: a dismissal during transcription or inference is as much a stop as one
     * after, and must neither hold a send nor reopen the microphone.
     */
    internal var stopsAtStart = 0
    internal var dismissalsAtStart = 0

    /** The capture coroutine, kept so a typed command can stop the microphone. */
    private var listening: Job? = null

    /** Everything after the microphone closes: transcribe, parse, act, speak. */
    internal var processing: Job? = null

    /**
     * Serialises loading the language model. It is started in the background as soon as
     * the session opens and again, if still needed, when a command arrives; without
     * this the two would race and load the model twice.
     */
    private val modelLoading = Mutex()

    /**
     * Outlives the session, for the work that must finish after it closes.
     *
     * The session's own scope belongs to the overlay's composition and is cancelled the
     * moment it goes away, which is exactly when the engines need freeing.
     */
    private val teardown = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The prompt-cache write, kept so the model is not freed out from under it. */
    private var cacheWrite: Job? = null

    /**
     * Guards the transcriber, which is one engine and cannot run twice at once.
     *
     * Partial passes take the lock only if it is free, so a slow one is skipped rather
     * than queued; the final pass waits, because its result is the one that matters.
     */
    internal val transcriber = Mutex()

    /** Whether this session has already written its prompt cache. */
    private var cacheWritten = false

    /** The caller's contact names for transcriber biasing, read once and reused; null until there are some. */
    private var contactBias: String? = null

    /** A messaging/calling command held while the user chooses among several contacts. */
    internal var pendingChoice: PendingChoice? = null

    @Suppress("VariableNaming", "ktlint:standard:backing-property-naming")
    internal val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Begins listening. Anything that can go wrong before the microphone opens is
     * reported with something the user can actually do about it, not a silent no-op.
     */
    fun start() {
        source = QuerySource.VOICE
        // Pause other apps' media the moment we are summoned: it would otherwise bleed into
        // the microphone and, later, play over the spoken reply.
        requestAudioFocus()
        val previous = listening
        listening = scope.launch {
            // Joined, not merely cancelled: a second summon before the first gave up used
            // to open a second recorder while the first still held the microphone, and
            // the device refuses the second one.
            //
            // Bounded, though. An unbounded join is worse than the contention it avoids:
            // if the previous capture is wedged in a native read that never returns, the
            // wait never ends, and every summon after it opens an overlay that never
            // listens and never says why. A stale recorder is the better failure, so
            // after the grace period this proceeds regardless.
            if (previous != null) {
                Log.i(TAG, "summon: waiting for the previous capture")
                val handedOver = withTimeoutOrNull(HANDOVER_MILLIS) { previous.cancelAndJoin() }
                if (handedOver == null) Log.w(TAG, "summon: previous capture would not stop")
            }
            // A new command replaces the old one, including a reply still being spoken.
            processing?.cancel()
            // Cancelling the job does not silence the voice: synthesis and playback are
            // blocking calls that run to the end regardless. A summon during a reply (the
            // same session, summoned again) opened the microphone on it, and the assistant
            // transcribed its own words. Stopped here, before the microphone opens; a reply
            // still being synthesised is dropped, and so is one from fail(), which runs
            // outside the job.
            piper.stop()
            _state.value = SessionState.Preparing
            if (!hasMicrophonePermission()) {
                fail(
                    "I cannot hear you without microphone access.",
                    "Grant Microphone under System access.",
                )
                return@launch
            }
            val sttModel = installed(ModelRole.STT)
            if (sttModel == null) {
                fail("No speech model is installed yet.", "Download one under Models.")
                return@launch
            }

            if (!whisper.isLoaded && !whisper.load(storage.target(sttModel))) {
                fail("The speech model could not be loaded.")
                return@launch
            }
            // Bias the transcriber toward the caller's contacts, so a name like "Hasan" is
            // heard as itself and not the nearest English word. Read once and reused, unless
            // it came back empty (no permission yet, no signal): that is tried again next time.
            whisper.setBias(
                contactBias ?: withContext(Dispatchers.IO) { ContactNames(context).forBias() }
                    .also { if (it.isNotEmpty()) contactBias = it },
            )
            // The voice is prepared in the background too. Unpacking it is a one-off
            // that costs a few seconds, and doing it here means the first reply is
            // spoken rather than silently skipped.
            prepareVoice()

            // The language model loads in the background while the user is still
            // speaking, so its load time overlaps the utterance instead of following it.
            scope.launch { ensureLanguageModel() }
            withContext(Dispatchers.IO) { prepareVoiceCheck() }
            listen()
        }
    }

    /**
     * Stops the microphone without ending the session, for someone who would rather type.
     *
     * The engines stay loaded: a typed command still needs the language model, and the
     * voice still speaks the reply.
     */
    fun stopListening() {
        stops++
        AssistantSounds.capturing = false
        // Cancelled but deliberately still referenced. Nulling it here threw away the only
        // handle on a recorder that had not finished releasing the microphone, so the next
        // summon opened a second one on top of it and the audio system made it queue —
        // two and a half seconds during which the overlay is up, looks ready, and hears
        // nothing at all. start() joins whatever is left here before opening its own.
        listening?.cancel()
        // A dismissal abandons a pending contact choice too: the held command is gone, so
        // a later tap on a stale selector must not send anything.
        // A pending send is kept: this also runs for the Type toggle, and a typed reply is still the
        // answer. A dismissal ends it in [dismissed].
        pendingChoice = null
        // Give media back. A typed command re-takes focus in submitText, since it still
        // speaks a reply; a dismissal leaves it released, so the music resumes.
        abandonAudioFocus()
        val current = _state.value
        if (current is SessionState.Listening || current is SessionState.Preparing) {
            _state.value = SessionState.Idle
        }
    }

    /** The overlay was dismissed: stops listening, and forgets the send waiting for its words. */
    fun dismissed() {
        PendingSend.dismiss()
        PendingReminder.dismiss()
        stopListening()
        // The command in flight is abandoned too. stopListening only ends the capture, so a "Text Sam"
        // dismissed while Thinking went on to ask for the words, hold the send and reopen the microphone.
        processing?.cancel()
        piper.stop()
    }

    /** Notes the stops and dismissals so far: what a command that began now must not have outlived. */
    private fun beginCommand() {
        stopsAtStart = stops
        dismissalsAtStart = PendingSend.dismissals()
    }

    /**
     * Runs a command the user typed rather than spoke.
     *
     * The microphone is stopped first: someone who has started typing has decided not to
     * talk, and a stray sound should not become a second command. Everything after that
     * is the same path speech takes, minus transcription.
     */
    fun submitText(text: String) {
        val typed = text.trim()
        if (typed.isEmpty()) return
        source = QuerySource.TYPED
        stopListening()
        // stopListening gave media back; a typed command still speaks, so take focus again.
        requestAudioFocus()
        beginCommand()
        processing = scope.launch {
            _state.value = SessionState.Thinking(typed)
            act(typed, sttMillis = 0)
        }
    }

    internal inline fun updateListening(block: (SessionState.Listening) -> SessionState) {
        val current = _state.value
        if (current is SessionState.Listening) _state.value = block(current)
    }

    internal suspend fun handle(event: CaptureEvent.Finished) {
        AssistantSounds.capturing = false
        beginCommand()
        if (event.samples.isEmpty()) {
            fail("I did not hear anything.")
            return
        }
        // A NO_SPEECH verdict is the energy gate's opinion, not a finding of fact, and it
        // is wrong whenever the room was misjudged. Whisper is asked before the user is
        // told they said nothing; if it also comes back empty, they really did.
        val gaveUp = event.reason == StopReason.NO_SPEECH
        AssistantSounds.play(AssistantSounds.Cue.DISENGAGE, volume = 0.4f)
        _state.value = SessionState.Transcribing

        val heard = transcriber.withLock { whisper.transcribe(event.samples) }
        // Blank is not the only way Whisper reports no words: music or a quiet room comes back
        // as "you", "Thank you." or "♪", and the router made alarms out of those. They get
        // exactly the answer a blank transcript gets.
        if (JunkTranscript.isJunk(heard.text)) {
            if (heard.text.isNotBlank()) Log.i(TAG, "dropping junk transcript [${heard.text}]")
            fail(if (gaveUp) "I did not hear anything." else "I heard something, but could not make out any words.")
            return
        }
        if (gaveUp) Log.i(TAG, "endpointer heard nothing, but whisper did")
        Log.i(TAG, "heard [${heard.text}] in ${heard.durationMillis} ms")
        _state.value = SessionState.Thinking(heard.text)
        act(heard.text, heard.durationMillis)
    }

    /**
     * Turns the transcript into a tool call and runs it.
     *
     * A phrase the assistant has resolved before is run at once, from the cache, before
     * the model is even consulted — or loaded. Everything else goes through the model,
     * and if the command it produces is one whose meaning is fixed, it is remembered for
     * next time.
     *
     * The grammar guarantees a valid call, so there is no retry loop: a null parse would
     * mean no grammar was applied, which is a programming error rather than a runtime
     * condition.
     */
    internal suspend fun act(transcript: String, sttMillis: Long) {
        if (answeredPendingSend(transcript, sttMillis) || answeredPendingReminder(transcript, sttMillis)) return
        if (answeredWithoutTheModel(transcript, sttMillis)) return

        if (!ensureLanguageModel()) {
            failCommand(
                transcript,
                "I heard you, but no language model is installed.",
                "Download one under Models.",
            )
            return
        }

        val prompt = PromptBuilder.build(transcript, AssistantTools.all)
        // Stop the moment the tool turns out to be a question: what the small model would
        // write next is exactly the part that gets replaced.
        val completion = llama.generate(
            prompt,
            ToolGrammar.build(AssistantTools.all),
            stopAt = PromptBuilder.ANSWER_MARKER,
        )
        Log.i(TAG, "model returned ${completion.text} in ${completion.millis} ms")

        var call = ToolCallParser.parse(completion.text)
        if (call == null) {
            failCommand(transcript, "I could not work out what to do with that.")
            return
        }
        var llmMillis = completion.millis

        if (call.tool == AssistantTools.ANSWER) {
            // One model in memory at a time. The small one has already done its work for
            // this command and is cheap to bring back; the large one is not, and the two
            // together do not fit alongside everything else the phone is running.
            llama.unload()
            val answer = router.answer(
                transcript,
                settings.adminNameNow(),
                files.contextForPrompt(),
                remote = settings.remoteLlmNow(),
            )
            call = if (answer != null) {
                llmMillis += answer.millis
                ToolCall(
                    AssistantTools.ANSWER,
                    buildMap {
                        put("text", answer.text)
                        // Shown under the reply: the user should always know when an answer
                        // came from off the phone.
                        answer.via?.let { put("via", it) }
                    },
                )
            } else {
                // Said out loud, so it has to name the actual obstacle: telling someone
                // to download a model they already have is worse than saying nothing.
                ToolCall(
                    AssistantTools.ANSWER,
                    mapOf(
                        "text" to when (router.lastRefusal) {
                            ModelRouter.Refusal.NO_MEMORY ->
                                "I need more free memory to answer questions."

                            ModelRouter.Refusal.FAILED ->
                                "The larger model would not load, so I cannot answer that."

                            else ->
                                "I can only do that with the larger model. It is under Models."
                        },
                    ),
                )
            }
        }
        carryOut(transcript, CallChecks.withoutEchoedRequest(transcript, call), sttMillis, llmMillis, Resolution.MODEL)
    }

    /**
     * The two ways a command is answered with no model at all.
     *
     * The clock and sums first. The phone knows the time exactly, and the small model asked
     * for it answered by reading out the notification shade — which was then learned, so it
     * kept doing it. A sum is worked out, not recalled. Then the remembered shapes, which is the
     * whole point of remembering them.
     */
    private suspend fun answeredWithoutTheModel(transcript: String, sttMillis: Long): Boolean {
        (LocalAnswers.of(transcript) ?: Arithmetic.answer(transcript))?.let { answer ->
            carryOut(
                transcript,
                ToolCall(AssistantTools.ANSWER, mapOf("text" to answer)),
                sttMillis,
                llmMillis = 0,
                resolution = Resolution.CACHE,
            )
            return true
        }
        val known = withContext(Dispatchers.IO) { cache.lookup(transcript) } ?: return false
        Log.i(TAG, "cache hit [$transcript] -> ${known.tool} ${known.arguments}")
        carryOut(transcript, known, sttMillis, llmMillis = 0, resolution = Resolution.CACHE)
        return true
    }

    /** Executes a resolved call, then records, remembers, shows and speaks the outcome. */
    internal suspend fun carryOut(
        transcript: String,
        call: ToolCall,
        sttMillis: Long,
        llmMillis: Long,
        resolution: Resolution,
    ) {
        val ready = CallChecks.reconciled(transcript, call)

        // Never pick among several matching contacts silently — "Hassan" matched both a
        // contact and "Dr Hassan ER Chicago", and a blind choice texted the wrong one. Hold
        // the command and let the user say who it was for.
        contactChoices(ready)?.let { choices ->
            pendingChoice = PendingChoice(transcript, ready, sttMillis, llmMillis, resolution)
            _state.value = SessionState.ChooseContact(ready.arguments["recipient"].orEmpty(), choices)
            return
        }

        var result = executor.execute(ready)
        // The executor asked for the words; remember who they are for.
        val askedForWords = ready.tool == AssistantTools.SEND_MESSAGE && !result.success &&
            ready.string("body") == null && holdSendFor(ready, dismissalsAtStart)
        // The executor asked what the reminder is for; the next words are the answer.
        val askedForTask = ready.tool == AssistantTools.CREATE_REMINDER && result.spoken == ToolExecutor.ASK_TASK &&
            PendingReminder.holdUnlessDismissed(transcript, dismissalsAtStart)

        result = summarisedScreen(transcript, call, result)

        // Learning and recording both touch the disk, and neither is worth a frame of
        // the reply. Only a call the model produced and that then succeeded is learned:
        // a failure might be a misread, and a cache hit is already known.
        val record = QueryRecord(
            atEpochMillis = System.currentTimeMillis(),
            transcript = transcript,
            source = source,
            resolution = resolution,
            tool = call.tool,
            arguments = call.arguments,
            spoken = result.spoken,
            success = result.success,
            sttMillis = sttMillis,
            llmMillis = llmMillis,
        )
        teardown.launch {
            if (resolution == Resolution.MODEL && result.success) cache.learn(transcript, call)
            history.append(record)
        }
        Log.i(
            TAG,
            "session done: ${call.tool} -> ${result.spoken} " +
                "(stt $sttMillis ms, llm $llmMillis ms, ${resolution.name.lowercase()})",
        )

        AssistantSounds.play(
            if (result.success) AssistantSounds.Cue.CONFIRM else AssistantSounds.Cue.REJECT,
            volume = 0.45f,
        )
        _state.value = SessionState.Done(
            transcript = transcript,
            tool = call.tool,
            result = result,
            timing = Timing(sttMillis, llmMillis),
            fromCache = resolution == Resolution.CACHE,
        )

        // Started while the user is listening to the answer, and on the scope that
        // outlives the session rather than the overlay's own. The write itself survives
        // either way — it is a native call, and cancelling the coroutine around it does
        // not stop it once it has begun — but on the overlay's scope it is only ever one
        // scheduling delay away from not starting at all.
        // Once per session is enough: the cached prefix is the same every time.
        if (!cacheWritten && llama.isLoaded) {
            cacheWritten = true
            cacheWrite = teardown.launch {
                llama.saveState()
                router.saveState()
            }
        }

        // Said after the state is published, so the reply is on screen while it is being
        // spoken rather than after.
        piper.speak(result.spoken)

        if (askedForWords || askedForTask) listenForAnswer()
    }

    /**
     * A question about the screen is answered, not recited. read_screen hands back the
     * full text as `detail`, and without this step the assistant reads the raw
     * accessibility dump out loud — which is exactly what "read the screen and
     * summarise it" produced: a minute of somebody else's chat log, unsummarised.
     * Done in carryOut rather than in act() so it covers the cached path too, and in the
     * session rather than the executor, which runs on the main thread and must never
     * be the thing that loads three gigabytes of weights.
     */
    private suspend fun summarisedScreen(transcript: String, call: ToolCall, result: ToolResult): ToolResult {
        val question = ScreenQuestion.of(transcript, call.arguments["question"])
        if (call.tool != AssistantTools.READ_SCREEN || question == null || !result.success) return result
        val screen = result.detail.orEmpty().ifBlank { result.spoken }
        llama.unload()
        val answered = router.answerAbout(screen, question, settings.adminNameNow())
        return if (answered != null) {
            result.copy(spoken = answered.text)
        } else {
            // The un-escalated result is still a real answer here, which is not true
            // of any other escalation in the app, so it degrades rather than refuses.
            result.copy(spoken = "I could not summarise that, so here is what it says. ${result.spoken}")
        }
    }

    /**
     * Loads the language model if it is not loaded, exactly once however many callers
     * ask at the same time. False only if there is no model to load or it will not load.
     */
    private suspend fun ensureLanguageModel(): Boolean = modelLoading.withLock {
        if (llama.isLoaded) return@withLock true
        val asset = installed(ModelRole.LLM) ?: return@withLock false
        llama.load(storage.target(asset))
    }

    /** A failure that happened to a command, so the transcript is worth keeping. */
    private fun failCommand(transcript: String, message: String, actionable: String? = null) =
        fail(message, actionable, transcript)

    internal fun fail(message: String, actionable: String? = null, transcript: String = "") {
        // An answer that never came: the next command must not be taken for what the reminder is about.
        PendingReminder.dismiss()
        // Recorded, not just spoken. The history held eleven entries and zero failures
        // over a period with more than a hundred failed summons, so the one log the user
        // can actually see was the one place the trouble never showed up.
        history.append(
            QueryRecord(
                atEpochMillis = System.currentTimeMillis(),
                transcript = transcript,
                source = source,
                resolution = Resolution.FAILED,
                spoken = message,
                success = false,
            ),
        )
        // Logged as well as shown. Every user-visible failure used to leave no trace at
        // all, which made a session that ended badly indistinguishable from one that
        // never ended, and both of them invisible to a test running over adb.
        Log.i(TAG, "session problem: $message${actionable?.let { " ($it)" } ?: ""}")
        _state.value = SessionState.Problem(message, actionable)
        AssistantSounds.play(AssistantSounds.Cue.REJECT)
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
    internal fun installed(role: ModelRole): ModelAsset? {
        val ready = registry.byRole(role).filter { storage.quickState(it) == ModelState.Ready }
        return ready.firstOrNull { it.isDefault } ?: ready.firstOrNull()
    }

    /** Frees both models. Called when the overlay goes away, not between utterances. */
    fun release() {
        // Media the assistant paused resumes when the session ends, not just on a dismiss.
        abandonAudioFocus()
        // The microphone is done the moment the overlay closes; drop the foreground state
        // with it rather than leave a "listening" notification up after the session is gone.
        MicForegroundService.stop(context)
        // The router stops itself promptly. Piper waits out a reply it is synthesising, and
        // whisper and llama the monitor an in-flight decode holds, and this is the main
        // thread closing an overlay, so freeing them is handed to a scope that outlives the
        // session. Stopping the voice is instant, and keeps a reply mid-synthesis silent.
        piper.stop()
        router.release()
        // Its own job: a long synthesis must not hold the models in memory while it ends.
        teardown.launch { piper.release() }
        teardown.launch {
            // Safe whenever it runs: a model that is released answers the capture still using it
            // with an error, which turns the check off for that capture.
            voiceModel.release()
            // Waited for, not raced: the save walks the very cells nativeFree releases,
            // and a use-after-free here is the same SIGSEGV that state saving already
            // cost once.
            cacheWrite?.join()
            executor.release()
            whisper.unload()
            llama.unload()
        }
        _state.value = SessionState.Idle
    }

    private companion object {
        const val TAG = "Assistant"

        /** How long a new summon waits for the previous recorder to let go. */
        const val HANDOVER_MILLIS = 400L
    }
}
