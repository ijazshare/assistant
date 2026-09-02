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
import io.github.hasanismail.themachine.tools.TimeResolver
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs real utterances through the real model on the phone.
 *
 * This is the test that matters for the parsing half of the pipeline: the grammar can
 * be correct and the prompt still be wrong, and only the model can say which. It is
 * skipped rather than failed when no model is installed, so a fresh checkout does not
 * report a failure for something that was never downloaded.
 */
@RunWith(AndroidJUnit4::class)
class ToolCallOnDeviceTest {

    companion object {
        private const val TAG = "TheMachine"
        private lateinit var engine: LlamaEngine
        private var available = false
        private var modelName = ""

        @BeforeClass
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
            // Loading once for the whole class: it is the dominant cost, and each test
            // clears the model's state anyway.
            available = runBlocking { engine.load(storage.target(asset)) }
        }

        @AfterClass
        @JvmStatic
        fun freeModel() {
            if (available) engine.unload()
        }
    }

    private fun parse(utterance: String): Pair<String, Map<String, String>> {
        assumeTrue("No language model installed on this device", available)
        val dialect = PromptDialect.forModel(modelName)
        val prompt = dialect.buildPrompt(
            transcript = utterance,
            tools = MachineTools.all,
            adminName = "Hasan",
            userContext = "- Osman is my brother.",
        )
        val completion = runBlocking { engine.generate(prompt, dialect.grammar(MachineTools.all)) }
        Log.i(TAG, "[$utterance] -> ${completion.text}  (${completion.millis} ms)")

        val call = dialect.parse(completion.text)
        assertThat(call).isNotNull()
        return call!!.tool to call.arguments
    }

    @Test
    fun everyOutputIsAValidToolCall() {
        assumeTrue(available)
        // The grammar's central claim: whatever is said, the output names a real tool.
        val utterances = listOf(
            "set an alarm for 7am",
            "ten minute timer",
            "remind me to call Osman at 6pm",
            "what did I miss",
            "open Spotify",
            "read the screen",
            "go back",
            "what is the airspeed velocity of an unladen swallow",
        )
        for (utterance in utterances) {
            val (tool, _) = parse(utterance)
            assertThat(MachineTools.all.map { it.name }).contains(tool)
        }
    }

    /** What the executor will actually set, rather than the raw field the model emitted. */
    private fun resolvedHour(args: Map<String, String>): Int? =
        TimeResolver.hourOf(args["hour"]?.toIntOrNull())

    private fun resolvedSeconds(args: Map<String, String>): Int? = TimeResolver.totalSeconds(
        args["hours"]?.toIntOrNull(),
        args["minutes"]?.toIntOrNull(),
        args["seconds"]?.toIntOrNull(),
    )

    @Test
    fun setsAnAlarmAtTheRightTime() {
        val (tool, args) = parse("set an alarm for 7 am")
        assertThat(tool).isEqualTo(MachineTools.SET_ALARM)
        assertThat(resolvedHour(args)).isEqualTo(7)
    }

    @Test
    fun understandsAfternoonTimes() {
        // The most common way a 24-hour conversion goes wrong. The model reports the
        // hour it heard and whether it was evening; the conversion is TimeResolver's.
        val (tool, args) = parse("wake me at half past six in the evening")
        assertThat(tool).isEqualTo(MachineTools.SET_ALARM)
        assertThat(resolvedHour(args)).isEqualTo(18)
    }

    @Test
    fun startsATimerInSeconds() {
        val (tool, args) = parse("set a timer for ten minutes")
        assertThat(tool).isEqualTo(MachineTools.SET_TIMER)
        assertThat(resolvedSeconds(args)).isEqualTo(600)
    }

    @Test
    fun createsAReminderWithItsTask() {
        val (tool, args) = parse("remind me to buy milk")
        assertThat(tool).isEqualTo(MachineTools.CREATE_REMINDER)
        assertThat(args["task"]).ignoringCase().contains("milk")
    }

    @Test
    fun opensAnAppByName() {
        val (tool, args) = parse("open the camera")
        assertThat(tool).isEqualTo(MachineTools.OPEN_APP)
        assertThat(args["app"]).ignoringCase().contains("camera")
    }

    @Test
    fun usesTheScreenToolsForScreenRequests() {
        assertThat(parse("what does this say").first).isEqualTo(MachineTools.READ_SCREEN)
        assertThat(parse("scroll down").first).isEqualTo(MachineTools.SCROLL)
    }

    @Test
    fun refusesRatherThanInventing() {
        // Nothing here books a flight. Saying so, answering in words, or writing it down
        // as something to do are all honest; quietly setting an alarm or a timer and
        // letting the user believe a flight was arranged is not, and that is what this
        // is guarding against.
        //
        // create_reminder is accepted deliberately. A 1B model files an errand it cannot
        // run as an errand to remember, and the confirmation it produces says exactly
        // that — "I will remind you to book flight to Cairo at 23:00" — so nothing is
        // claimed that did not happen. Demanding a refusal here would be insisting on a
        // less useful answer for the sake of a tidier rule.
        val (tool, _) = parse("book me a flight to Cairo next Tuesday")
        assertThat(tool).isAnyOf(
            MachineTools.UNSUPPORTED,
            MachineTools.ANSWER,
            MachineTools.CREATE_REMINDER,
        )
    }
}
