/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.llm

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import io.github.hasanismail.themachine.models.ModelAsset
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.settings.RemoteLlmConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Chooses which model handles what, without ever adding a model call to the common path.
 *
 * The small model's tool choice is the routing decision. It is forced by the grammar to
 * begin `{"tool":"<name>"`, which it reaches in a few tokens, and every command — alarms,
 * timers, reminders, apps, the screen — is carried out from that alone. Only a question
 * goes further: the small model is a good judge of "this wants words, not an action" and
 * a poor writer of them, answering six test questions with three echoes, a wrong refusal
 * and two misroutes. So a question is handed to the larger model, when one is installed
 * and the phone has room for it, and declined honestly when not.
 */
class ModelRouter(private val context: Context) {

    /** Outlives any one session, so a cache write is not cancelled by the overlay closing. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val storage = ModelStorage(context)
    private val registry = ModelRegistry(context)
    private val strong = LlamaEngine(context)
    private val remoteAnswerer = RemoteAnswerer()
    private val loading = Mutex()

    /** Whether this router has already written the larger model's prompt cache. */
    private var cacheWritten = false

    /** File name of the larger model, or null if none is installed. */
    val strongModel: ModelAsset?
        get() {
            val ready = registry.byRole(ModelRole.LLM).filter { storage.quickState(it) == ModelState.Ready }
            val fast = ready.firstOrNull { it.isDefault } ?: ready.firstOrNull() ?: return null
            // The biggest installed model, which may be the one already answering
            // commands. "Biggest that is not the default" picked a 270M experiment over
            // the 1B when both were installed, and left a lone 4B unable to answer at all.
            return ready.maxByOrNull { it.byteSize }?.takeIf { it.byteSize >= fast.byteSize }
        }

    val isStrongLoaded: Boolean get() = strong.isLoaded

    /** Why a question could not be answered, for a reply that says something useful. */
    enum class Refusal { NO_MODEL, NO_MEMORY, FAILED }

    var lastRefusal: Refusal? = null
        private set

    /**
     * Answers a question about text captured from the screen.
     *
     * Shares the loading gate, the unload discipline and the cache write with [answer] by
     * delegating to it; only the prompt and the length differ, because a summary of a
     * screen needs more room than a fact does and must be confined to the text given.
     */
    suspend fun answerAbout(screen: String, question: String, adminName: String): Completion? {
        val trimmed = screen.takeLast(ScreenPrompt.SCREEN_BUDGET)
        return generateWith(
            ScreenPrompt.build(trimmed, question, adminName, clipped = trimmed.length < screen.length),
            ScreenPrompt.MAX_TOKENS,
            // A screen summary is allowed its second sentence; a fact is not.
            oneSentence = false,
        )
    }

    /**
     * Answers a question with the larger model, or returns null if it cannot: no such
     * model, not enough memory to hold it beside the small one, or a failed load.
     *
     * The memory gate matters more than it looks. The weights are mapped, not copied, and
     * a phone that is short of memory reclaims exactly those pages first — mid-reply,
     * which turns each token into a re-read from flash.
     */
    suspend fun answer(
        question: String,
        adminName: String,
        userContext: String,
        remote: RemoteLlmConfig? = null,
    ): Completion? {
        // A configured remote model is the stronger answerer and costs the phone no memory,
        // and only the question text crosses the wire. Any failure falls through to the
        // on-device path exactly as if no remote existed, so a dead network is never a dead
        // assistant.
        if (remote != null) {
            remoteAnswerer.answer(remote, question, adminName, userContext)?.let { return it }
            Log.i(TAG, "remote model unavailable; falling back to the on-device model")
        }
        return generateWith(AnswerPrompt.build(question, adminName, userContext), AnswerPrompt.MAX_TOKENS)
    }

    private suspend fun generateWith(
        prompt: String,
        maxTokens: Int,
        oneSentence: Boolean = true,
    ): Completion? {
        lastRefusal = null
        val asset = strongModel
        if (asset == null) {
            lastRefusal = Refusal.NO_MODEL
            return null
        }
        if (!ensureLoaded(asset)) return null
        val completion = strong.generate(prompt = prompt, grammar = "", maxTokens = maxTokens)
        val spoken = completion.text.substringBefore("<end_of_turn>").trim()
        val text = if (oneSentence) firstSentence(spoken) else spoken
        Log.i(TAG, "strong answered in ${completion.millis} ms: $text")

        if (text.isBlank()) return null

        // The cache write is several megabytes and the answer is already in hand, so
        // both it and the unload happen after the caller has the reply.
        val reply = completion.copy(text = text)
        // Freed as soon as it has answered. Holding three gigabytes of weights alongside
        // the small model pushed the app into swap on an 11 GB phone — measured at 200 MB
        // free and 1.2 GB swapped — and every command after the question decoded at under
        // one token a second. A question costs a reload; a command must not.
        //
        // Under the same lock that guards loading: launched loose, this unload raced the
        // next question's load and one answer in four came back empty.
        scope.launch {
            loading.withLock {
                if (!cacheWritten) {
                    cacheWritten = true
                    strong.saveState()
                }
                strong.unload()
                cacheWritten = false
            }
        }
        return reply
    }

    /**
     * The first sentence, and nothing after it.
     *
     * The token cap can stop the model mid-word, and a spoken half-sentence is worse
     * than a short one. A cap-truncated reply with no terminator is kept whole rather
     * than thrown away, because the first clause is usually the answer.
     */
    private fun firstSentence(text: String): String {
        val end = SENTENCE_END.find(text)?.range?.first ?: return text
        return text.substring(0, end + 1).trim()
    }

    /** Forces the prompt cache out now, for a caller that is about to shut everything down. */
    suspend fun saveState() {
        if (strong.isLoaded && !cacheWritten) {
            cacheWritten = true
            strong.saveState()
        }
    }

    private suspend fun ensureLoaded(asset: ModelAsset): Boolean = loading.withLock {
        if (strong.isLoaded) return@withLock true
        val available = availableBytes()
        if (available < asset.byteSize + HEADROOM_BYTES) {
            lastRefusal = Refusal.NO_MEMORY
            Log.w(
                TAG,
                "strong model skipped: ${available / MIB} MiB free, need ${(asset.byteSize + HEADROOM_BYTES) / MIB}",
            )
            return@withLock false
        }
        val started = System.nanoTime()
        val ok = strong.load(storage.target(asset))
        if (!ok) lastRefusal = Refusal.FAILED
        Log.i(
            TAG,
            "strong model ${if (ok) "loaded" else "failed"} in ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms",
        )
        ok
    }

    private fun availableBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService<ActivityManager>()?.getMemoryInfo(info) ?: return Long.MAX_VALUE
        return info.availMem
    }

    fun release() {
        // Off the caller's thread: unload takes the monitor an in-flight decode holds,
        // and that caller is the main thread closing the overlay.
        scope.launch { strong.unload() }
        cacheWritten = false
    }

    private companion object {
        const val TAG = "TheMachine"

        /**
         * What the model needs beyond its own weights: the key-value cache (about
         * 270 MB at this context size) and the compute buffer (about 160 MB), plus room
         * for the rest of the app.
         *
         * It was 1200 MB, set while both models were resident. With one at a time that
         * is no longer the shape of the problem, and it refused to answer on a phone with
         * four gigabytes free — asking for 4208 MB when 4156 were available, so every
         * question came back "I need more free memory".
         */
        const val HEADROOM_BYTES = 640L * 1024 * 1024
        const val MIB = 1024L * 1024

        /**
         * A sentence ends at a full stop that is not inside a number.
         *
         * Cutting at the first '.' turned "Pi is approximately 3.14159." into "Pi is
         * approximately 3." — a correct answer made wrong on the way to being spoken.
         */
        val SENTENCE_END = Regex("""[.!?](?=\s|$)""")
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
