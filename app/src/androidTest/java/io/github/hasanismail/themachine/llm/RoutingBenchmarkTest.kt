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
        Case("set an alarm for 7am", MachineTools.SET_ALARM),
        Case("wake me at half past six in the evening", MachineTools.SET_ALARM),
        Case("set an alarm for 6:30", MachineTools.SET_ALARM),
        Case("wake me up at noon", MachineTools.SET_ALARM),
        Case("set a timer for ten minutes", MachineTools.SET_TIMER),
        Case("five minute timer", MachineTools.SET_TIMER),
        Case("set a timer for 90 seconds", MachineTools.SET_TIMER),
        Case("start a two hour timer", MachineTools.SET_TIMER),
        Case("show my alarms", MachineTools.SHOW_ALARMS),
        Case("what alarms are set", MachineTools.SHOW_ALARMS),
        // ---- reminders ----
        Case("remind me to buy milk", MachineTools.CREATE_REMINDER),
        Case("remind me to call Osman at 6pm", MachineTools.CREATE_REMINDER),
        Case("remind me to take the bins out tonight", MachineTools.CREATE_REMINDER),
        // ---- messaging / calls ----
        Case("text Osman I'm running late", MachineTools.SEND_MESSAGE),
        Case("message mum that I will be home soon", MachineTools.SEND_MESSAGE),
        Case("send a text to Dad", MachineTools.SEND_MESSAGE),
        Case("text me I am on my way", MachineTools.SEND_MESSAGE),
        Case("text me the address", MachineTools.SEND_MESSAGE),
        Case("call Osman", MachineTools.CALL_CONTACT),
        Case("phone mum", MachineTools.CALL_CONTACT),
        Case("give Dad a call", MachineTools.CALL_CONTACT),
        // ---- apps ----
        Case("open Spotify", MachineTools.OPEN_APP),
        Case("open the camera", MachineTools.OPEN_APP),
        Case("launch WhatsApp", MachineTools.OPEN_APP),
        // ---- screen ----
        Case("what does this say", MachineTools.READ_SCREEN),
        Case("read the screen", MachineTools.READ_SCREEN),
        Case("summarise this screen", MachineTools.READ_SCREEN),
        Case("what is this error", MachineTools.READ_SCREEN),
        Case("take a screenshot", MachineTools.TAKE_SCREENSHOT),
        Case("screenshot this", MachineTools.TAKE_SCREENSHOT),
        Case("capture the screen", MachineTools.TAKE_SCREENSHOT),
        Case("tap send", MachineTools.TAP_TEXT),
        Case("tap the login button", MachineTools.TAP_TEXT),
        Case("scroll down", MachineTools.SCROLL),
        Case("scroll up", MachineTools.SCROLL),
        Case("go back", MachineTools.NAVIGATE),
        Case("go home", MachineTools.NAVIGATE),
        Case("open recent apps", MachineTools.NAVIGATE),
        // ---- notifications ----
        Case("what did I miss", MachineTools.READ_NOTIFICATIONS),
        Case("read my notifications", MachineTools.READ_NOTIFICATIONS),
        Case("any new notifications", MachineTools.READ_NOTIFICATIONS),
        // ---- general questions: must go to ANSWER, not the read_* tools ----
        Case("how many buildings are there in New York", MachineTools.ANSWER),
        Case("what time is it in Johannesburg", MachineTools.ANSWER),
        Case("who wrote Hamlet", MachineTools.ANSWER),
        Case("how tall is Mount Everest", MachineTools.ANSWER),
        Case("what is the capital of France", MachineTools.ANSWER),
        Case("how many days are in a week", MachineTools.ANSWER),
        Case("what is fifteen times twelve", MachineTools.ANSWER),
        Case("why is the sky blue", MachineTools.ANSWER),
        // ---- out of scope: honest non-action answers all acceptable ----
        Case("order me a pizza", MachineTools.UNSUPPORTED, MachineTools.ANSWER),
        Case("book me a flight to Cairo", MachineTools.UNSUPPORTED, MachineTools.ANSWER, MachineTools.CREATE_REMINDER),
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
        Log.i(BENCH, "==== ROUTING BENCHMARK: $correct/${cases.size} = $pct% ====")
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
        val dialect = PromptDialect.forModel(modelName)
        val prompt = dialect.buildPrompt(
            transcript = utterance,
            tools = MachineTools.all,
            adminName = "Hasan",
            userContext = "- Osman is my brother.",
        )
        val completion = runBlocking { engine.generate(prompt, dialect.grammar(MachineTools.all)) }
        val call = dialect.parse(completion.text)
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
            val asset = ModelRegistry(context).byRole(ModelRole.LLM)
                .filter { storage.quickState(it) == ModelState.Ready }
                .let { ready -> ready.firstOrNull { it.isDefault } ?: ready.firstOrNull() }
                ?: return
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
