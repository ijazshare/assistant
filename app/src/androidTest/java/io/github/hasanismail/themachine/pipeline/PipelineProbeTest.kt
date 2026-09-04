/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.pipeline

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hasanismail.themachine.llm.LlamaEngine
import io.github.hasanismail.themachine.llm.PromptDialect
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.settings.MachineSettings
import io.github.hasanismail.themachine.tools.ContactLookup
import io.github.hasanismail.themachine.tools.MachineTools
import io.github.hasanismail.themachine.tools.MessageBody
import io.github.hasanismail.themachine.tools.ReminderStore
import io.github.hasanismail.themachine.tools.ToolCall
import io.github.hasanismail.themachine.tools.ToolExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real pipeline from a typed command, so the routing-plus-execution half can be
 * exercised over adb without speaking. Deliberately opt-in and inert by default:
 *
 *  - `-e runcmd "<command>"`      parse it the way the voice path does; log the tool and args.
 *  - `-e smsto <number> -e smsbody "<text>"`  build a send_message call for that number.
 *  - `-e live true`               actually carry the call out (sends the SMS). Without it, dry.
 *
 * Nothing here runs in ordinary CI: with no runcmd and no smsto it skips.
 */
@RunWith(AndroidJUnit4::class)
class PipelineProbeTest {

    @Test
    fun probe() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (dryLookup(args, context)) return
        val live = args.getString("live") == "true"

        assumeTrue("No language model installed", available)
        val smsto = args.getString("smsto")
        val runcmd = args.getString("runcmd")
        assumeTrue("Pass -e runcmd, -e smsto or -e resolve to drive the pipeline", smsto != null || runcmd != null)

        var call: ToolCall? = when {
            smsto != null -> ToolCall(
                MachineTools.SEND_MESSAGE,
                mapOf("recipient" to smsto, "body" to (args.getString("smsbody") ?: "test")),
            )

            else -> parse(runcmd!!)
        }
        Log.i(PIPE, "input=[${smsto ?: runcmd}] -> tool=${call?.tool} args=${call?.arguments}")
        if (call == null) return

        // Mirror the session's guard: never send a body the user did not actually say.
        if (call.tool == MachineTools.SEND_MESSAGE && runcmd != null) {
            val body = call.arguments["body"].orEmpty()
            if (body.isNotBlank() && !MessageBody.isTraceable(runcmd, body)) {
                Log.i(PIPE, "dropped invented body \"$body\"")
                call = call.copy(arguments = call.arguments - "body")
            }
        }

        if (!live) {
            Log.i(PIPE, "DRY RUN — pass -e live true to execute")
            return
        }
        val executor = ToolExecutor(context, ReminderStore(context), ContactLookup(context))
        val result = runBlocking { executor.execute(call) }
        Log.i(PIPE, "EXECUTED success=${result.success} spoken=[${result.spoken}] detail=[${result.detail}]")
    }

    /**
     * The contact-side probes, which need no model and never send anything:
     *
     *  - `-e setown <number>` saves the owner's number exactly as the Context screen would.
     *  - `-e resolve "<name>|<name>"` reports what each name resolves to, reading that saved
     *    number the way the session does, so "me" is checked end to end.
     *
     * Returns true if either ran, so the caller skips the model path entirely.
     */
    private fun dryLookup(args: Bundle, context: Context): Boolean {
        args.getString("setown")?.let { number ->
            runBlocking { MachineSettings(context).setOwnNumber(number) }
            Log.i(PIPE, "own number saved (${number.count { it.isDigit() }} digits)")
        }
        val names = args.getString("resolve") ?: return args.containsKey("setown")
        val own = runBlocking { MachineSettings(context).ownNumberNow() }
        val lookup = ContactLookup(context) { own }
        for (one in names.split("|")) {
            Log.i(PIPE, "resolve [$one] -> ${lookup.resolveNumber(one)}")
        }
        return true
    }

    private fun parse(command: String): ToolCall? {
        val dialect = PromptDialect.forModel(modelName)
        val prompt = dialect.buildPrompt(command, MachineTools.all, adminName = "Admin", userContext = "")
        val completion = runBlocking {
            engine.generate(prompt, dialect.grammar(MachineTools.all), stopAt = dialect.answerMarker)
        }
        Log.i(PIPE, "raw=${completion.text}")
        return dialect.parse(completion.text)
    }

    companion object {
        private const val PIPE = "PIPE"
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
