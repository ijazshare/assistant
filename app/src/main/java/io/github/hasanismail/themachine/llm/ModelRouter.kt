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

    private val storage = ModelStorage(context)
    private val registry = ModelRegistry(context)
    private val strong = LlamaEngine(context)
    private val loading = Mutex()

    /** File name of the larger model, or null if none is installed. */
    val strongModel: ModelAsset?
        get() {
            val ready = registry.byRole(ModelRole.LLM).filter { storage.quickState(it) == ModelState.Ready }
            val fast = ready.firstOrNull { it.isDefault } ?: ready.firstOrNull()
            return ready.filter { it !== fast }.maxByOrNull { it.byteSize }
        }

    val isStrongLoaded: Boolean get() = strong.isLoaded

    /**
     * Answers a question with the larger model, or returns null if it cannot: no such
     * model, not enough memory to hold it beside the small one, or a failed load.
     *
     * The memory gate matters more than it looks. The weights are mapped, not copied, and
     * a phone that is short of memory reclaims exactly those pages first — mid-reply,
     * which turns each token into a re-read from flash.
     */
    suspend fun answer(question: String, adminName: String, userContext: String): Completion? {
        val asset = strongModel ?: return null
        if (!ensureLoaded(asset)) return null
        val completion = strong.generate(
            prompt = AnswerPrompt.build(question, adminName, userContext),
            grammar = "",
            maxTokens = AnswerPrompt.MAX_TOKENS,
        )
        val text = completion.text.substringBefore("<end_of_turn>").trim()
        Log.i(TAG, "strong answered in ${completion.millis} ms: $text")
        return if (text.isBlank()) null else completion.copy(text = text)
    }

    /** Written after the first answer so the next session skips the prefill. */
    suspend fun saveState() {
        if (strong.isLoaded) strong.saveState()
    }

    private suspend fun ensureLoaded(asset: ModelAsset): Boolean = loading.withLock {
        if (strong.isLoaded) return@withLock true
        val available = availableBytes()
        if (available < asset.byteSize + HEADROOM_BYTES) {
            Log.w(
                TAG,
                "strong model skipped: ${available / MIB} MiB free, need ${(asset.byteSize + HEADROOM_BYTES) / MIB}",
            )
            return@withLock false
        }
        val started = System.nanoTime()
        val ok = strong.load(storage.target(asset))
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
        strong.unload()
    }

    private companion object {
        const val TAG = "TheMachine"

        /** Context, compute buffer and the rest of the app, over the mapped weights. */
        const val HEADROOM_BYTES = 1_200L * 1024 * 1024
        const val MIB = 1024L * 1024
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
