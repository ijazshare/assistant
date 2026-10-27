/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.ui.debug

import android.content.Context
import io.github.ijazshare.assistant.context.AssistantFiles
import io.github.ijazshare.assistant.llm.LlamaEngine
import io.github.ijazshare.assistant.llm.PromptDialect
import io.github.ijazshare.assistant.models.ModelRegistry
import io.github.ijazshare.assistant.models.ModelRole
import io.github.ijazshare.assistant.models.ModelState
import io.github.ijazshare.assistant.models.ModelStorage
import io.github.ijazshare.assistant.settings.AssistantSettings
import io.github.ijazshare.assistant.tools.AssistantTools
import io.github.ijazshare.assistant.tools.ToolCall

/** One probe run: what the model emitted and what it parsed to. */
data class ProbeResult(
    val input: String,
    val raw: String,
    val call: ToolCall?,
    val millis: Long,
    val error: String? = null,
)

/**
 * Runs the parsing half of the pipeline from typed text.
 *
 * Exists so the grammar and prompt can be exercised without speaking into the phone —
 * the thing that most needs iterating is how the model reads a phrase, and re-recording
 * "remind me to call Osman at six" fifty times is a poor way to find that out.
 *
 * Debug source set only; it does not exist in a release build.
 */
class LlmProbe(private val context: Context) {

    private val engine = LlamaEngine(context)
    private var modelName = ""
    private val storage = ModelStorage(context)
    private val registry = ModelRegistry(context)
    private val settings = AssistantSettings(context)
    private val files = AssistantFiles(context)

    suspend fun run(input: String): ProbeResult {
        if (!engine.isLoaded) {
            val ready = registry.byRole(ModelRole.LLM)
                .filter { storage.quickState(it) == ModelState.Ready }
            val asset = (ready.firstOrNull { it.isDefault } ?: ready.firstOrNull())
                ?: return ProbeResult(input, "", null, 0, "No language model installed.")
            modelName = asset.fileName
            if (!engine.load(storage.target(asset))) {
                return ProbeResult(input, "", null, 0, "Model failed to load.")
            }
        }

        val dialect = PromptDialect.forModel(modelName)
        val prompt = dialect.buildPrompt(
            transcript = input,
            tools = AssistantTools.all,
            adminName = settings.adminNameNow(),
            userContext = files.contextForPrompt(),
        )
        val completion = engine.generate(prompt, dialect.grammar(AssistantTools.all))
        return ProbeResult(
            input = input,
            raw = completion.text,
            call = dialect.parse(completion.text),
            millis = completion.millis,
        )
    }

    suspend fun describe(): String = engine.describe()

    fun release() = engine.unload()
}
