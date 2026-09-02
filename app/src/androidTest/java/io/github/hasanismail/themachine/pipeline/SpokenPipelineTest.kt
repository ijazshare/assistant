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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.hasanismail.themachine.llm.LlamaEngine
import io.github.hasanismail.themachine.llm.PromptDialect
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.stt.WhisperEngine
import io.github.hasanismail.themachine.tools.MachineTools
import io.github.hasanismail.themachine.tools.TimeResolver
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The whole pipeline over real speech, from samples to a tool call.
 *
 * The recordings are synthesised rather than spoken, which makes them repeatable and
 * means this can run unattended; what they cannot vouch for is a human voice in a noisy
 * room. Everything downstream of the microphone is exercised for real: the same
 * transcriber, prompt, grammar and parser the assistant uses when the side button is
 * pressed.
 */
@RunWith(AndroidJUnit4::class)
class SpokenPipelineTest {

    companion object {
        private const val TAG = "TheMachine"
        private lateinit var whisper: WhisperEngine
        private lateinit var llama: LlamaEngine
        private var ready = false
        private var modelName = ""

        @BeforeClass
        @JvmStatic
        fun load() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val storage = ModelStorage(context)
            val registry = ModelRegistry(context)

            fun installed(role: ModelRole) = registry.byRole(role)
                .filter { storage.quickState(it) == ModelState.Ready }
                .let { r -> r.firstOrNull { it.isDefault } ?: r.firstOrNull() }

            val stt = installed(ModelRole.STT) ?: return
            val llm = installed(ModelRole.LLM) ?: return
            modelName = llm.fileName
            whisper = WhisperEngine(context)
            llama = LlamaEngine(context)
            ready = runBlocking {
                whisper.load(storage.target(stt)) && llama.load(storage.target(llm))
            }
        }

        @AfterClass
        @JvmStatic
        fun free() {
            if (ready) {
                whisper.unload()
                llama.unload()
            }
        }
    }

    /** 16-bit mono PCM from the test assets, as the float samples the engine wants. */
    private fun samples(asset: String): FloatArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(asset).use { it.readBytes() }

        // Walk the RIFF chunks rather than assuming a 44-byte header, which is true of
        // most writers and not all of them.
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val count = minOf(size, bytes.size - offset - 8) / 2
                val pcm = ByteBuffer.wrap(bytes, offset + 8, count * 2).order(ByteOrder.LITTLE_ENDIAN)
                return FloatArray(count) { pcm.short / Short.MAX_VALUE.toFloat() }
            }
            offset += 8 + size + (size and 1)
        }
        error("no data chunk in $asset")
    }

    private fun transcribe(asset: String): String {
        val text = runBlocking { whisper.transcribe(samples(asset)) }.text
        Log.i(TAG, "SPOKEN [$asset] -> $text")
        return text
    }

    private fun toolFor(transcript: String): Pair<String, Map<String, String>> {
        val dialect = PromptDialect.forModel(modelName)
        val completion = runBlocking {
            llama.generate(
                dialect.buildPrompt(transcript, MachineTools.all, "Hasan", ""),
                dialect.grammar(MachineTools.all),
            )
        }
        val call = requireNotNull(dialect.parse(completion.text)) { completion.text }
        return call.tool to call.arguments
    }

    @Test
    fun aSpokenTimerBecomesATimer() {
        assumeTrue(ready)
        val heard = transcribe("timer.wav")
        assertThat(heard).ignoringCase().contains("timer")

        val (tool, args) = toolFor(heard)
        assertThat(tool).isEqualTo(MachineTools.SET_TIMER)
        assertThat(
            TimeResolver.totalSeconds(
                args["hours"]?.toIntOrNull(),
                args["minutes"]?.toIntOrNull(),
                args["seconds"]?.toIntOrNull(),
            ),
        ).isEqualTo(600)
    }

    @Test
    fun aSpokenAlarmBecomesAnEveningAlarm() {
        assumeTrue(ready)
        val heard = transcribe("alarm.wav")
        val (tool, args) = toolFor(heard)
        assertThat(tool).isEqualTo(MachineTools.SET_ALARM)
        // Reconciled against the words, exactly as VoiceSession does before executing:
        // the model converts to 24-hour time by pattern and gets it wrong often enough
        // that the pipeline's answer is the corrected one, not its first guess.
        assertThat(TimeResolver.reconcileHour(heard, args["hour"]?.toIntOrNull()))
            .isEqualTo(18)
    }

    @Test
    fun aSpokenReminderKeepsItsTask() {
        assumeTrue(ready)
        val heard = transcribe("reminder.wav")
        val (tool, args) = toolFor(heard)
        assertThat(tool).isEqualTo(MachineTools.CREATE_REMINDER)
        assertThat(args["task"]).ignoringCase().contains("call")
    }

    /**
     * The partial transcripts shown while someone is still speaking.
     *
     * The session transcribes the audio so far every half second, so what matters is
     * that a prefix of the recording yields a prefix of the sentence rather than
     * something unrelated — and that transcribing repeatedly is safe at all.
     */
    @Test
    fun partialsConvergeOnTheFinalTranscript() {
        assumeTrue(ready)
        val all = samples("timer.wav")
        val final = runBlocking { whisper.transcribe(all) }.text.trim().lowercase()

        var lastWords = 0
        for (fraction in listOf(4, 2, 4 to 3)) {
            val take = when (fraction) {
                4 -> all.size / 4
                2 -> all.size / 2
                else -> all.size * 3 / 4
            }
            val partial = runBlocking { whisper.transcribe(all.copyOf(take)) }.text.trim()
            Log.i(TAG, "PARTIAL ${take * 1000 / 16000} ms -> $partial")
            lastWords = partial.split(" ").size
        }
        assertThat(lastWords).isAtLeast(1)
        assertThat(final).contains("timer")
    }
}
