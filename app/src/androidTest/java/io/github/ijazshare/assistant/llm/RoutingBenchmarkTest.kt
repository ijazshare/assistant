/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.llm

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.ijazshare.assistant.models.ModelRegistry
import io.github.ijazshare.assistant.models.ModelRole
import io.github.ijazshare.assistant.models.ModelState
import io.github.ijazshare.assistant.models.ModelStorage
import io.github.ijazshare.assistant.tools.AssistantTools
import io.github.ijazshare.assistant.tools.ToolGrammar
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A labelled routing benchmark: many utterances, each with the tool (or tools) it should
 * resolve to, run through the real model on the phone. This is the ground truth the chain
 * was missing — every earlier regression was found by hand. Logs a per-tool scoreboard
 * and every miss under the tag BENCH, so a prompt change can be measured, not guessed.
 */
@RunWith(AndroidJUnit4::class)
class RoutingBenchmarkTest {

    /** One utterance and the tool(s) that count as correct for it. */
    private data class Case(val text: String, val expect: Set<String>) {
        constructor(text: String, vararg expect: String) : this(text, expect.toSet())
    }

    private val cases = listOf(
        // ---- alarms / timers ----
        Case("set an alarm for 7am", AssistantTools.SET_ALARM),
        Case("wake me at half past six in the evening", AssistantTools.SET_ALARM),
        Case("set an alarm for 6:30", AssistantTools.SET_ALARM),
        Case("wake me up at noon", AssistantTools.SET_ALARM),
        Case("set a timer for ten minutes", AssistantTools.SET_TIMER),
        Case("five minute timer", AssistantTools.SET_TIMER),
        Case("set a timer for 90 seconds", AssistantTools.SET_TIMER),
        Case("start a two hour timer", AssistantTools.SET_TIMER),
        Case("show my alarms", AssistantTools.SHOW_ALARMS),
        Case("what alarms are set", AssistantTools.SHOW_ALARMS),
        // ---- reminders ----
        Case("remind me to buy milk", AssistantTools.CREATE_REMINDER),
        Case("remind me to call Osman at 6pm", AssistantTools.CREATE_REMINDER),
        Case("remind me to take the bins out tonight", AssistantTools.CREATE_REMINDER),
        // ---- messaging / calls ----
        Case("text Osman I'm running late", AssistantTools.SEND_MESSAGE),
        Case("message mum that I will be home soon", AssistantTools.SEND_MESSAGE),
        Case("send a text to Dad", AssistantTools.SEND_MESSAGE),
        Case("text me I am on my way", AssistantTools.SEND_MESSAGE),
        Case("text me the address", AssistantTools.SEND_MESSAGE),
        Case("text me", AssistantTools.SEND_MESSAGE),
        Case("send a message", AssistantTools.SEND_MESSAGE),
        Case("Text me this is a test.", AssistantTools.SEND_MESSAGE),
        // The exact phrasings that misrouted live (to read_screen, answer, set_alarm).
        Case("Text Hassan that my thing is working.", AssistantTools.SEND_MESSAGE),
        Case("text Hassan saying hello", AssistantTools.SEND_MESSAGE),
        Case("text mum I will be late", AssistantTools.SEND_MESSAGE),
        Case("call Osman", AssistantTools.CALL_CONTACT),
        Case("phone mum", AssistantTools.CALL_CONTACT),
        Case("give Dad a call", AssistantTools.CALL_CONTACT),
        // ---- apps ----
        Case("open Spotify", AssistantTools.OPEN_APP),
        Case("open the camera", AssistantTools.OPEN_APP),
        Case("launch WhatsApp", AssistantTools.OPEN_APP),
        // ---- screen ----
        Case("what does this say", AssistantTools.READ_SCREEN),
        Case("read the screen", AssistantTools.READ_SCREEN),
        Case("summarise this screen", AssistantTools.READ_SCREEN),
        Case("what is this error", AssistantTools.READ_SCREEN),
        Case("take a screenshot", AssistantTools.TAKE_SCREENSHOT),
        Case("screenshot this", AssistantTools.TAKE_SCREENSHOT),
        Case("capture the screen", AssistantTools.TAKE_SCREENSHOT),
        Case("tap send", AssistantTools.TAP_TEXT),
        Case("tap the login button", AssistantTools.TAP_TEXT),
        Case("scroll down", AssistantTools.SCROLL),
        Case("scroll up", AssistantTools.SCROLL),
        Case("go back", AssistantTools.NAVIGATE),
        Case("go home", AssistantTools.NAVIGATE),
        Case("open recent apps", AssistantTools.NAVIGATE),
        // ---- notifications ----
        Case("what did I miss", AssistantTools.READ_NOTIFICATIONS),
        Case("read my notifications", AssistantTools.READ_NOTIFICATIONS),
        Case("any new notifications", AssistantTools.READ_NOTIFICATIONS),
        // ---- general questions: must go to ANSWER, not the read_* tools ----
        Case("how many buildings are there in New York", AssistantTools.ANSWER),
        Case("what time is it in Johannesburg", AssistantTools.ANSWER),
        Case("who wrote Hamlet", AssistantTools.ANSWER),
        Case("how tall is Mount Everest", AssistantTools.ANSWER),
        Case("what is the capital of France", AssistantTools.ANSWER),
        Case("how many days are in a week", AssistantTools.ANSWER),
        Case("what is fifteen times twelve", AssistantTools.ANSWER),
        Case("why is the sky blue", AssistantTools.ANSWER),
        // ---- out of scope: honest non-action answers all acceptable ----
        Case("order me a pizza", AssistantTools.UNSUPPORTED, AssistantTools.ANSWER),
        Case(
            "book me a flight to Cairo",
            AssistantTools.UNSUPPORTED,
            AssistantTools.ANSWER,
            AssistantTools.CREATE_REMINDER,
        ),
    )

    @Test
    fun scoreboard() {
        assumeTrue("No language model installed", available)

        val missesByExpected = LinkedHashMap<String, Int>()
        val totalsByExpected = LinkedHashMap<String, Int>()
        var correct = 0
        val misses = StringBuilder()

        for (case in cases) {
            val primary = case.expect.first()
            totalsByExpected[primary] = (totalsByExpected[primary] ?: 0) + 1
            val got = parseTool(case.text)
            if (got in case.expect) {
                correct++
            } else {
                missesByExpected[primary] = (missesByExpected[primary] ?: 0) + 1
                misses.appendLine("  MISS  \"${case.text}\"  expected ${case.expect}  got $got")
            }
        }

        val pct = correct * 100 / cases.size
        Log.i(BENCH, "==== ROUTING BENCHMARK [$modelName]: $correct/${cases.size} = $pct% ====")
        for ((tool, total) in totalsByExpected) {
            val missed = missesByExpected[tool] ?: 0
            Log.i(BENCH, "  $tool: ${total - missed}/$total")
        }
        if (misses.isNotEmpty()) Log.i(BENCH, "MISSES:\n$misses")

        // Soft floor so the run always completes and the scoreboard is the deliverable;
        // tighten once the chain is fixed.
        assertThat(correct).isGreaterThan(0)
    }

    private fun parseTool(utterance: String): String {
        val prompt = PromptBuilder.build(
            transcript = utterance,
            tools = AssistantTools.all,
        )
        val completion = runBlocking {
            engine.generate(prompt, ToolGrammar.build(AssistantTools.all), stopAt = PromptBuilder.ANSWER_MARKER)
        }
        val call = ToolCallParser.parse(completion.text)
        return call?.tool ?: "PARSE_FAIL"
    }

    companion object {
        private const val BENCH = "BENCH"
        private lateinit var engine: LlamaEngine
        private var available = false
        private var modelName = ""

        @org.junit.BeforeClass
        @JvmStatic
        fun loadModel() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val storage = ModelStorage(context)
            val ready = ModelRegistry(context).byRole(ModelRole.LLM)
                .filter { storage.quickState(it) == ModelState.Ready }
            // Pass `-e model <file-name fragment>` to benchmark a specific model; otherwise
            // the default router model.
            val wanted = InstrumentationRegistry.getArguments().getString("model")
            val asset = when {
                wanted != null -> ready.firstOrNull { it.fileName.contains(wanted, ignoreCase = true) }
                else -> ready.firstOrNull { it.isDefault } ?: ready.firstOrNull()
            } ?: return
            modelName = asset.fileName
            engine = LlamaEngine(context)
            available = runBlocking { engine.load(storage.target(asset)) }
        }

        @org.junit.AfterClass
        @JvmStatic
        fun freeModel() {
            if (available) engine.unload()
        }
    }
}
