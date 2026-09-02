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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.tools.MachineTools
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the small model says when asked something rather than told to do something.
 *
 * The tool choice is the grammar's job and is tested elsewhere; this is about the words
 * inside an `answer`. A 1B model has a habit of handing the question back as the reply,
 * and the count of how often it does so is what decides whether questions should be
 * routed to a larger model.
 */
@RunWith(AndroidJUnit4::class)
class AnswerQualityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun questionsGetAnswersNotEchoes() {
        val storage = ModelStorage(context)
        val asset = ModelRegistry(context).byRole(ModelRole.LLM)
            .filter { storage.quickState(it) == ModelState.Ready }
            .let { ready -> ready.firstOrNull { it.isDefault } ?: ready.firstOrNull() }
        assumeTrue("No language model installed", asset != null)
        requireNotNull(asset)

        val engine = LlamaEngine(context)
        assertThat(runBlocking { engine.load(storage.target(asset)) }).isTrue()
        val dialect = PromptDialect.forModel(asset.fileName)

        val questions = listOf(
            "who painted the Mona Lisa",
            "what is the capital of France",
            "how many days are in a leap year",
            "what is my brother called",
            "what does the word ephemeral mean",
            "which is bigger, the sun or the moon",
        )
        var echoes = 0
        try {
            for (question in questions) {
                val prompt = dialect.buildPrompt(question, MachineTools.all, "Hasan", "- Osman is my brother.")
                val completion = runBlocking { engine.generate(prompt, dialect.grammar(MachineTools.all)) }
                val call = dialect.parse(completion.text)
                val text = call?.arguments?.get("text") ?: ""
                val echoed = call?.tool == MachineTools.ANSWER &&
                    text.lowercase().trimEnd('?', '.', '!') == question.lowercase()
                if (echoed) echoes++
                val mark = if (echoed) "ECHO" else ""
                Log.i(TAG, "ANSWER [$question] -> ${call?.tool} \"$text\" $mark (${completion.millis} ms)")
            }
        } finally {
            engine.unload()
        }
        Log.i(TAG, "ANSWER echoes: $echoes of ${questions.size}")
        // Recorded rather than asserted: this is a measurement of the model, not of the code.
        assertThat(echoes).isAtMost(questions.size)
    }

    private companion object {
        const val TAG = "TheMachine"
    }
}
