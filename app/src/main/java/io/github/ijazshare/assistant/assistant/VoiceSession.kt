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
import android.content.Context
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
import io.github.ijazshare.assistant.audio.StopReason
import io.github.ijazshare.assistant.audio.VoiceCheckLayer
import io.github.ijazshare.assistant.audio.VoiceRecorder
import io.github.ijazshare.assistant.context.AssistantFiles
import io.github.ijazshare.assistant.history.CaptureArchive
import io.github.ijazshare.assistant.history.QueryLog
import io.github.ijazshare.assistant.history.QueryRecord
import io.github.ijazshare.assistant.history.QuerySource
import io.github.ijazshare.assistant.history.Resolution
import io.github.ijazshare.assistant.llm.LlamaEngine
import io.github.ijazshare.assistant.llm.ModelRouter
import io.github.ijazshare.assistant.llm.PromptDialect
import io.github.ijazshare.assistant.models.ModelArchive
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
import io.github.ijazshare.assistant.tools.ContactResolution
import io.github.ijazshare.assistant.tools.MessageBody
import io.github.ijazshare.assistant.tools.ReminderStore
import io.github.ijazshare.assistant.tools.StatedTime
import io.github.ijazshare.assistant.tools.TimeResolver
import io.github.ijazshare.assistant.tools.ToolCall
import io.github.ijazshare.assistant.tools.ToolExecutor
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
class VoiceSession(private val context: Context, private val scope: CoroutineScope) {

    // Debug builds keep every capture for diagnosing end-of-speech (CaptureArchive), written
    // off the microphone's thread; release builds skip even the copy.
    private val captures = CaptureArchive(context)
    private val recorder = VoiceRecorder(
        onCaptured = if (captures.enabled) {
            { c ->
                teardown.launch { captures.save(System.currentTimeMillis(), c) }
            }
        } else {
            null
        },
    )
    private val whisper = WhisperEngine(context)
    private val llama = LlamaEngine(context)
    private val storage = ModelStorage(context)
    private val registry = ModelRegistry(context)
    private val settings = AssistantSettings(context)
    private val files = AssistantFiles(context)

    /**
     * The owner's saved number, mirrored from settings so the lookup can read it without
     * blocking: "text me" goes here, never to whichever contact fuzzily matched "me".
     */
    private var ownNumber: String? = null
    private val contacts = ContactLookup(context) { ownNumber }
    private val recency = ContactRecency(context)
    private val executor = ToolExecutor(context, ReminderStore(context), contacts)

    init {
        scope.launch { settings.ownNumber.collect { ownNumber = it } }
    }

    private val piper = PiperEngine()

    /**
     * The voice check's model, loaded once the first time it is wanted, if the optional download is
     * there. Empty runs the energy endpointer alone. A file that is not the verified model, or would
     * not load, is not retried.
     */
    private val voiceModel = VoiceModelSlot<SileroVoiceModel> { it.release() }

    private val router = ModelRouter(context)

    /** Held while the assistant is active so other apps' media pauses, kept so it can be given back. */
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var audioFocus: AudioFocusRequest? = null
    private val cache = CommandCache.shared(File(context.getExternalFilesDir(null), CommandCache.FILE_NAME))
    private val history = QueryLog(context)

    /** Where the current command came from, for the record. */
    private var source = QuerySource.VOICE

    /**
     * Counts every time the microphone was stopped on purpose, a dismissal, a switch to typing
     * or a typed command alike, so a reply that would reopen it can tell it was told not to.
     */
    private var stops = 0

    /**
     * [stops] and the dismissals as they stood when the current command began, not when the model
     * was done with it: a dismissal during transcription or inference is as much a stop as one
     * after, and must neither hold a send nor reopen the microphone.
     */
    private var stopsAtStart = 0
    private var dismissalsAtStart = 0

    /** The capture coroutine, kept so a typed command can stop the microphone. */
    private var listening: Job? = null

    /** Everything after the microphone closes: transcribe, parse, act, speak. */
    private var processing: Job? = null

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
    private val transcriber = Mutex()

    /** File name of whatever language model is loaded, for dialect selection. */
    private var loadedModelName: String = ""

    /** Whether this session has already written its prompt cache. */
    private var cacheWritten = false

    /** The caller's contact names for transcriber biasing, read once and reused; null until there are some. */
    private var contactBias: String? = null

    /** A messaging/calling command held while the user chooses among several contacts. */
    private var pendingChoice: PendingChoice? = null

    private data class PendingChoice(
        val transcript: String,
        val call: ToolCall,
        val sttMillis: Long,
        val llmMillis: Long,
        val resolution: Resolution,
    )

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
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
     * Loads the voice check's model, if the optional download is installed. Never fails the
     * session: without it the microphone closes on the energy endpointer alone, as it always has.
     *
     * [SileroVoiceModel.open] hashes the file before it is handed to native code: a model with
     * another window size ends the process.
     */
    private fun prepareVoiceCheck() {
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
    private suspend fun listen() {
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
    private suspend fun act(transcript: String, sttMillis: Long) {
        if (answeredPendingSend(transcript, sttMillis)) return
        if (answeredWithoutTheModel(transcript, sttMillis)) return

        if (!ensureLanguageModel()) {
            failCommand(
                transcript,
                "I heard you, but no language model is installed.",
                "Download one under Models.",
            )
            return
        }

        // The dialect owns the prompt, the grammar and the parser together, because a
        // fine-tuned model only behaves if all three match the form it was trained on.
        val dialect = PromptDialect.forModel(loadedModelName)
        val prompt = dialect.buildPrompt(
            transcript = transcript,
            tools = AssistantTools.all,
            adminName = settings.adminNameNow(),
            userContext = files.contextForPrompt(),
        )
        // Stop the moment the tool turns out to be a question: what the small model would
        // write next is exactly the part that gets replaced.
        val completion = llama.generate(prompt, dialect.grammar(AssistantTools.all), stopAt = dialect.answerMarker)
        Log.i(TAG, "model returned ${completion.text} in ${completion.millis} ms")

        var call = dialect.parse(completion.text)
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
        carryOut(transcript, call, sttMillis, llmMillis, Resolution.MODEL)
    }

    /**
     * Checks the model's call against the words it was given: declines a screen read
     * nobody asked for, drops a message body nobody said, and corrects an hour the model
     * converted badly.
     *
     * The only place the transcript and the resolved call are both in hand, which is
     * what this needs: the executor sees a call and never the sentence behind it.
     */
    private fun reconciled(transcript: String, call: ToolCall): ToolCall {
        // Nobody asked about the screen, so reading it out answers nothing; a decline at
        // least says so. Unsuccessful, so the guess is never learned either.
        if (call.tool == AssistantTools.READ_SCREEN && !ScreenQuestion.pointsAtScreen(transcript)) {
            Log.i(TAG, "declining read_screen, nothing points at the screen in [$transcript]")
            return ToolCall(AssistantTools.UNSUPPORTED, mapOf("reason" to "For the screen, say \"read this\"."))
        }

        // A message body the user never said is invented, not dictated; drop it so the
        // executor asks for the words rather than sending "hello how are you" to someone.
        // So is one that is the request itself ("Now send that message to Sam"), which the
        // user did say, but as the command and not as the message.
        if (call.tool == AssistantTools.SEND_MESSAGE) {
            val body = call.arguments["body"].orEmpty()
            val recipient = call.arguments["recipient"].orEmpty()
            val display = call.arguments["display"].orEmpty()
            if (body.isNotBlank() &&
                (
                    MessageBody.isRequest(body, "$recipient $display") ||
                        !MessageBody.isTraceable(transcript, body, recipient)
                    )
            ) {
                Log.i(TAG, "dropping invented body \"$body\" for [$transcript]")
                return call.copy(arguments = call.arguments - "body")
            }
        }

        if (call.tool == AssistantTools.SET_ALARM || call.tool == AssistantTools.CREATE_REMINDER) {
            return withSpokenTime(transcript, call)
        }
        return call
    }

    /**
     * Replaces the model's time with the one the words state: "in 30 minutes" is now plus thirty
     * minutes, and "at 9:35" is 9:35, whatever the model made of them, but only when the transcript
     * states exactly one time. With none or several the model's numbers stand, with only the am/pm
     * correction of the hour it always had.
     */
    private fun withSpokenTime(transcript: String, call: ToolCall): ToolCall {
        // The tools have only today and tomorrow, so the model cannot have a day past tomorrow right.
        if (StatedTime.beyondTomorrow(transcript)) {
            Log.i(TAG, "declining a day past tomorrow in [$transcript]")
            return ToolCall(AssistantTools.UNSUPPORTED, mapOf("reason" to "I can only set that for today or tomorrow."))
        }
        val stated = call.arguments["hour"]?.toIntOrNull()
        val minute = call.arguments["minute"]?.toIntOrNull()
        val spoken = StatedTime.of(transcript, stated, minute) ?: return withReconciledHour(transcript, call, stated)
        val fixes = buildMap {
            if (spoken.hour != stated) put("hour", spoken.hour.toString())
            if (spoken.minute != (minute ?: 0)) put("minute", spoken.minute.toString())
            if (spoken.tomorrow != null && spoken.tomorrow != call.bool("tomorrow")) {
                put("tomorrow", spoken.tomorrow.toString())
            }
        }
        if (fixes.isEmpty()) return call
        Log.i(TAG, "time corrected by $fixes (model said $stated:$minute) for [$transcript]")
        return call.copy(arguments = call.arguments + fixes)
    }

    /** The model's time stands, with only the hour put right against an am, pm or part of day said. */
    private fun withReconciledHour(transcript: String, call: ToolCall, stated: Int?): ToolCall {
        val corrected = stated?.let { TimeResolver.reconcileHour(transcript, it) }
        if (corrected == null || corrected == stated) return call
        Log.i(TAG, "hour corrected from $stated to $corrected for [$transcript]")
        return call.copy(arguments = call.arguments + ("hour" to corrected.toString()))
    }

    /**
     * If a send is waiting for its words, this transcript is them, or a change of mind. If one is
     * waiting for its yes, this is the yes.
     *
     * Ahead of the cache and the router: "I'll be there at six" is a message here, and
     * would be a reminder anywhere else. Used once, expired or not, so the transcript after
     * it is a command again.
     */
    private suspend fun answeredPendingSend(transcript: String, sttMillis: Long): Boolean {
        val now = System.currentTimeMillis()
        val waiting = PendingSend.take(now) ?: return false
        val send = ToolCall(
            AssistantTools.SEND_MESSAGE,
            mapOf(
                "recipient" to waiting.number,
                "display" to waiting.display,
                "body" to (waiting.body ?: transcript.trim()),
            ),
        )
        var readBack = false
        val call = when {
            waiting.body != null ->
                if (PendingSend.isYes(transcript)) {
                    send
                } else {
                    ToolCall(AssistantTools.ANSWER, mapOf("text" to "Okay, I didn't send it."))
                }

            PendingSend.isCancel(transcript, waiting.display) ->
                ToolCall(AssistantTools.ANSWER, mapOf("text" to "Okay, I won't send it."))

            // "No, Bob": the name was misheard, and the correction is not a message for the first contact.
            PendingSend.isCorrection(transcript, waiting.display) ->
                ToolCall(AssistantTools.ANSWER, mapOf("text" to "Okay, I won't send it. Say the whole command again."))

            // "Text Bob please" is an order that landed in the answer slot, and "Text me when you land" is
            // dictation; which one it is cannot be told from the words, so it is read back, not sent.
            PendingSend.startsWithVerb(transcript) -> {
                val reply = transcript.trim()
                readBack =
                    PendingSend.holdUnlessDismissed(waiting.copy(body = reply, askedAtMillis = now), dismissalsAtStart)
                ToolCall(AssistantTools.ANSWER, mapOf("text" to "Send \"$reply\" to ${waiting.display}?"))
            }

            else -> send
        }
        // A read-back answered yes sends the words that were read back, so they are checked against
        // the dictation they came from: checked against the "yes", every word looks invented and is dropped.
        val said = waiting.body?.takeIf { call === send } ?: transcript
        // Resolution.CACHE: no model was involved, and nothing here is learned.
        carryOut(said, call, sttMillis, llmMillis = 0, resolution = Resolution.CACHE)
        if (readBack) listenForAnswer()
        return true
    }

    /**
     * Notes who a "What should I say to X?" was asked for, if X is one person.
     *
     * An unknown name or one that matched several (the contact chooser has run by now, and
     * what it picked arrives here as a number) gets no pending send: the next words are not
     * obviously for anyone, so they are a command.
     */
    private suspend fun holdSendFor(call: ToolCall, dismissalsBefore: Int): Boolean {
        val spoken = call.string("recipient") ?: return false
        val number = withContext(Dispatchers.IO) { contacts.resolveNumber(spoken) } ?: return false
        val send = PendingSend(number, call.string("display") ?: spoken, System.currentTimeMillis())
        // Not if the owner dismissed the overlay since the command began.
        return PendingSend.holdUnlessDismissed(send, dismissalsBefore)
    }

    /**
     * The two ways a command is answered with no model at all.
     *
     * The clock first: the phone knows the time exactly, and the small model asked for it
     * answered by reading out the notification shade — which was then learned, so it kept
     * doing it. Then the remembered shapes, which is the whole point of remembering them.
     */
    private suspend fun answeredWithoutTheModel(transcript: String, sttMillis: Long): Boolean {
        LocalAnswers.of(transcript)?.let { answer ->
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

    /**
     * The user picked one of several matching contacts. Carry the held command out to them,
     * with the number to send to and the name to say back.
     */
    fun chooseContact(contact: Contact) {
        val pending = pendingChoice ?: return
        pendingChoice = null
        val chosen = pending.call.copy(
            arguments = pending.call.arguments + mapOf("recipient" to contact.number, "display" to contact.name),
        )
        processing = scope.launch {
            carryOut(pending.transcript, chosen, pending.sttMillis, pending.llmMillis, pending.resolution)
        }
    }

    /**
     * The contacts to choose between, if a messaging or calling command's recipient matched
     * several people — null for any other tool or an unambiguous name. Reads the contacts,
     * SMS and call logs off the main thread, ordered so the person most recently texted or
     * called is first (and so survives the cap).
     */
    private suspend fun contactChoices(call: ToolCall): List<Contact>? {
        val messaging = call.tool == AssistantTools.SEND_MESSAGE || call.tool == AssistantTools.CALL_CONTACT
        val recipient = call.arguments["recipient"]?.takeIf { it.isNotBlank() }
        if (!messaging || recipient == null) return null
        return withContext(Dispatchers.IO) {
            (contacts.resolve(recipient) as? ContactResolution.Many)?.options?.let(recency::sortByRecency)
        }
    }

    /** Executes a resolved call, then records, remembers, shows and speaks the outcome. */
    private suspend fun carryOut(
        transcript: String,
        call: ToolCall,
        sttMillis: Long,
        llmMillis: Long,
        resolution: Resolution,
    ) {
        val ready = reconciled(transcript, call)

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

        if (askedForWords) listenForAnswer()
    }

    /**
     * Straight back to the microphone, so the owner can answer the question just asked without summoning
     * again. start() is the ordinary summon, and a stop since this command began (the overlay dismissed,
     * a switch to typing) means they did not want it.
     */
    private fun listenForAnswer() {
        if (source == QuerySource.VOICE && stops == stopsAtStart) start()
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
        loadedModelName = asset.fileName
        llama.load(storage.target(asset))
    }

    /** A failure that happened to a command, so the transcript is worth keeping. */
    private fun failCommand(transcript: String, message: String, actionable: String? = null) =
        fail(message, actionable, transcript)

    private fun fail(message: String, actionable: String? = null, transcript: String = "") {
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
    private fun installed(role: ModelRole): ModelAsset? {
        val ready = registry.byRole(role).filter { storage.quickState(it) == ModelState.Ready }
        return ready.firstOrNull { it.isDefault } ?: ready.firstOrNull()
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Takes audio focus so other apps' media pauses while the assistant is active — both to
     * keep the microphone clean and so the reply is heard rather than talked over. Transient
     * exclusive, so others pause rather than duck, and it is given straight back when done.
     */
    private fun requestAudioFocus() {
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

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        audioFocus?.let { manager.abandonAudioFocusRequest(it) }
        audioFocus = null
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
