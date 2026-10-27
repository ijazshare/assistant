/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random
import kotlin.math.exp
import kotlin.math.nextUp

/**
 * The voice check, tested without a model: a stub says what each window of audio is, and the
 * energy part is either scripted or the real [Endpointer] over synthetic levels. Nothing here is
 * a recording.
 */
class VoiceCheckLayerTest {

    private val frame = 320
    private val silence = FloatArray(frame)

    /** An energy part with scripted verdicts: speech begins on frame [began], and it stops on frame [stop]. */
    private class Scripted(
        private val began: Int? = null,
        private val stop: Int? = null,
        private val reason: StopReason = StopReason.ENDPOINT,
    ) {
        var frames = 0
        val inputs = ArrayList<Float>()

        fun accept(rms: Float, @Suppress("UNUSED_PARAMETER") highBandRms: Float): FrameVerdict {
            frames++
            inputs.add(rms)
            return when (frames) {
                stop -> FrameVerdict.Stop(reason)
                began -> FrameVerdict.SpeechBegan
                else -> FrameVerdict.Continue
            }
        }
    }

    /** A model whose probability for window k is [script] of k, remembering every window it was given. */
    private class StubModel(private val script: (Int) -> Float) : VoiceModel {
        val windows = ArrayList<FloatArray>()
        var resets = 0

        override fun probability(window: FloatArray): Float {
            windows.add(window.copyOf())
            return script(windows.size - 1)
        }

        override fun reset() {
            resets++
            windows.clear()
        }
    }

    private class Outcome(val began: Int?, val stop: Int?, val reason: StopReason?, val stoppedBy: Any?) {
        override fun toString() = "began=$began stop=$stop reason=$reason by=$stoppedBy"
    }

    /** Feeds frames 1..[frames]; the first stop ends it. */
    private fun run(
        layer: VoiceCheckLayer,
        frames: Int = 1_000,
        rms: (Int) -> Float = { 0f },
        samples: (Int) -> FloatArray = { silence },
    ): Outcome {
        var began: Int? = null
        for (f in 1..frames) {
            val x = samples(f)
            when (val verdict = layer.accept(rms(f), 0f, x, x.size)) {
                FrameVerdict.SpeechBegan -> if (began == null) began = f
                is FrameVerdict.Stop -> return Outcome(began, f, verdict.reason, layer.stoppedBy)
                FrameVerdict.Continue -> Unit
            }
        }
        return Outcome(began, null, null, layer.stoppedBy)
    }

    private fun layer(energy: Scripted, model: VoiceModel?) = VoiceCheckLayer(energy::accept, model)

    /** The frame on which window [k] completes, with full 320-sample reads. */
    private fun windowFrame(k: Int) = 15 + (576 + 512 * k + frame - 1) / frame

    // ---- windows ---------------------------------------------------------------------------------

    @Test
    fun `windows complete on frames 17, 19, 20, 22, 24, 25, 27, 28 and never two on one frame`() {
        val model = StubModel { 0f }
        val layer = layer(Scripted(), model)
        val completed = ArrayList<Int>()
        for (f in 1..200) {
            val before = model.windows.size
            layer.accept(0f, 0f, silence, frame)
            assertThat(model.windows.size - before).isAtMost(1)
            if (model.windows.size > before) completed.add(f)
        }
        assertThat(completed.take(8)).containsExactly(17, 19, 20, 22, 24, 25, 27, 28).inOrder()
        assertThat(completed).containsExactlyElementsIn(completed.indices.map { windowFrame(it) }).inOrder()
    }

    @Test
    fun `window k is filtered samples 512k to 512k+576, fed from frame 16, filter running since mic open`() {
        val random = Random(7)
        val raw = FloatArray(frame * 60) { (random.nextGaussian() * 0.05).toFloat() }
        val filter = HighPass.voiceCheck()
        val filtered = FloatArray(raw.size) { filter.process(raw[it]) }
        val fed = filtered.copyOfRange(15 * frame, filtered.size)
        val model = StubModel { 0f }
        val layer = layer(Scripted(), model)
        for (f in 0 until 60) layer.accept(0f, 0f, raw.copyOfRange(f * frame, (f + 1) * frame), frame)

        assertThat(model.windows.size).isGreaterThan(5)
        model.windows.forEachIndexed { k, window ->
            assertThat(window).isEqualTo(fed.copyOfRange(512 * k, 512 * k + 576))
        }
    }

    @Test
    fun `the model is only ever given exactly 576 samples, whatever the read sizes`() {
        // The native library ends the whole process on any other length.
        val model = StubModel { 0.5f }
        val layer = layer(Scripted(began = 16), model)
        val random = Random(9)
        val sizes = intArrayOf(320, 320, 320, 160, 1, 319)
        repeat(400) {
            val n = sizes[random.nextInt(sizes.size)]
            layer.accept(0f, 0f, FloatArray(n), n)
        }
        assertThat(model.windows.size).isGreaterThan(50)
        assertThat(model.windows.map { it.size }.toSet()).containsExactly(576)
    }

    @Test
    fun `windows depend on the samples, not on how they were read`() {
        val random = Random(3)
        val raw = FloatArray(frame * 40) { (random.nextGaussian() * 0.05).toFloat() }
        fun windowsFor(read: Int): List<FloatArray> {
            val model = StubModel { 0f }
            val layer = layer(Scripted(), model)
            // The lead-in is counted in reads, so start after it: only the sample stream is compared.
            repeat(15) { layer.accept(0f, 0f, FloatArray(read), read) }
            for (i in raw.indices step read) layer.accept(0f, 0f, raw.copyOfRange(i, i + read), read)
            return model.windows
        }
        val whole = windowsFor(320)
        val halves = windowsFor(160)
        assertThat(halves).hasSize(whole.size)
        whole.indices.forEach { assertThat(halves[it]).isEqualTo(whole[it]) }
    }

    // ---- the rule --------------------------------------------------------------------------------

    /** The window the check ends a capture on (energy began on frame 16), given [ps], then 0.5 forever; null if never. */
    private fun endsAt(ps: List<Float>): Int? {
        val layer = layer(Scripted(began = 16), StubModel { ps.getOrElse(it) { 0.5f } })
        val outcome = run(layer, 1_000)
        return outcome.stop?.let { stop -> (0 until 400).first { windowFrame(it) == stop } }
    }

    @Test
    fun `never above 0·9 means never armed, however long the silence`() {
        assertThat(endsAt(List(20) { 0.85f } + List(200) { 0f })).isNull()
    }

    @Test
    fun `it arms on three windows in a row above 0·9`() {
        assertThat(endsAt(listOf(0.95f, 0.95f, 0.5f, 0.95f, 0.95f) + List(100) { 0f })).isNull()
        assertThat(endsAt(List(3) { 0.95f } + List(25) { 0f })).isEqualTo(27)
    }

    @Test
    fun `0·2 counts as silent, and a window just above it starts the count again`() {
        val ps = List(3) { 0.95f } + List(24) { 0.2f } + 0.2f.nextUp() + List(25) { 0f }
        assertThat(endsAt(ps)).isEqualTo(52)
        assertThat(endsAt(List(3) { 0.95f } + List(25) { 0.2f })).isEqualTo(27)
    }

    @Test
    fun `0·9 does not arm, the next float up does`() {
        assertThat(endsAt(List(3) { 0.9f } + List(30) { 0f })).isNull()
        assertThat(endsAt(List(3) { 0.9f.nextUp() } + List(30) { 0f })).isNotNull()
    }

    @Test
    fun `silence before arming does not carry over`() {
        assertThat(endsAt(List(30) { 0f } + List(3) { 0.95f } + List(24) { 0f })).isNull()
    }

    // ---- with the energy part --------------------------------------------------------------------

    @Test
    fun `with no model the verdicts are the real endpointer's, frame for frame`() {
        val random = Random(11)
        repeat(200) { trial ->
            val rms = FloatArray(1_000) { exp(random.nextGaussian() * 1.2 - 5.0).toFloat() }
            if (trial % 2 == 1) rms.fill(0.05f, 40, 120)
            val alone = Endpointer(leadInMillis = Endpointer.MIC_SETTLE_MILLIS)
            val layered = VoiceCheckLayer(Endpointer(leadInMillis = Endpointer.MIC_SETTLE_MILLIS)::accept, null)
            for (f in 0 until 1_000) {
                val a = alone.accept(rms[f])
                val b = layered.accept(rms[f], 0f, silence, frame)
                assertThat(b).isEqualTo(a)
                if (a is FrameVerdict.Stop) break
            }
            assertThat(layered.active).isFalse()
        }
    }

    @Test
    fun `the energy part is given the same levels with or without a model`() {
        val rms = FloatArray(300) { 0.001f + it * 0.00016f }
        val with = Scripted(began = 30)
        val without = Scripted(began = 30)
        run(layer(with, StubModel { if (it < 10) 0.99f else 0f }), 300, rms = { rms[it - 1] })
        run(layer(without, null), 300, rms = { rms[it - 1] })

        val n = minOf(with.inputs.size, without.inputs.size)
        assertThat(n).isGreaterThan(0)
        assertThat(with.inputs.take(n)).isEqualTo(without.inputs.take(n))
    }

    @Test
    fun `an energy stop comes back unchanged even when the check would end on the same frame`() {
        val fire = windowFrame(27)
        assertThat(fire).isEqualTo(60)
        for (reason in listOf(StopReason.ENDPOINT, StopReason.NO_SPEECH, StopReason.MAX_DURATION)) {
            val layer = layer(
                Scripted(began = 16, stop = fire, reason = reason),
                StubModel {
                    if (it <
                        3
                    ) {
                        0.99f
                    } else {
                        0f
                    }
                },
            )
            val outcome = run(layer)
            assertThat(outcome.stop).isEqualTo(fire)
            assertThat(outcome.reason).isEqualTo(reason)
            assertThat(outcome.stoppedBy).isEqualTo(VoiceCheckLayer.StoppedBy.ENERGY)
        }
    }

    @Test
    fun `the check ends the capture on the frame the 25th quiet window completes`() {
        val layer = layer(Scripted(began = 16), StubModel { if (it < 3) 0.99f else 0f })
        val outcome = run(layer)
        assertThat(windowFrame(27)).isEqualTo(60)
        assertThat(outcome.began).isEqualTo(16)
        assertThat(outcome.stop).isEqualTo(60)
        assertThat(outcome.reason).isEqualTo(StopReason.ENDPOINT)
        assertThat(outcome.stoppedBy).isEqualTo(VoiceCheckLayer.StoppedBy.VOICE)
        assertThat(layer.armedFrame).isEqualTo(windowFrame(2))
        assertThat(layer.report().armed).isTrue()
    }

    @Test
    fun `windows before speech began are ignored, the one completing on that frame counts`() {
        // Voice in windows 0-2 (frames 17-20), speech begins on frame 22: nothing armed, nothing ends.
        val late = layer(Scripted(began = 22), StubModel { if (it < 3) 0.99f else 0f })
        assertThat(run(late, 400).stop).isNull()
        assertThat(late.report().armed).isFalse()
        // Speech begins on frame 20, where window 2 completes; voice in windows 0-4 arms on window 4.
        val onTime = layer(Scripted(began = 20), StubModel { if (it < 5) 0.99f else 0f })
        val outcome = run(onTime)
        assertThat(outcome.stop).isEqualTo(windowFrame(4 + 25))
        assertThat(outcome.stoppedBy).isEqualTo(VoiceCheckLayer.StoppedBy.VOICE)
    }

    @Test
    fun `it can only end a capture sooner, and only with an endpoint`() {
        val random = Random(5)
        val values = floatArrayOf(0f, 0.1f, 0.3f, 0.95f, 0.99f)
        repeat(300) {
            val began = 16 + random.nextInt(185)
            val stop = if (random.nextBoolean()) null else began + 1 + random.nextInt(900 - began)
            val ps = List(700) { values[random.nextInt(values.size)] }
            val alone = run(layer(Scripted(began, stop), null))
            val layered = run(layer(Scripted(began, stop), StubModel { ps[it] }))

            assertThat(layered.began).isEqualTo(alone.began)
            if (alone.stop != null) assertThat(layered.stop!!).isAtMost(alone.stop)
            if (layered.stop != null && (alone.stop == null || layered.stop < alone.stop)) {
                assertThat(layered.reason).isEqualTo(StopReason.ENDPOINT)
                assertThat(layered.stoppedBy).isEqualTo(VoiceCheckLayer.StoppedBy.VOICE)
            }
        }
    }

    @Test
    fun `a model that throws turns the check off for the rest of the capture`() {
        val model = StubModel {
            check(it != 4) { "onnx" }
            if (it < 3) 0.99f else 0f
        }
        val layer = layer(Scripted(began = 16), model)

        assertThat(run(layer, 600).stop).isNull()
        assertThat(layer.active).isFalse()
        assertThat(layer.failure).hasMessageThat().isEqualTo("onnx")
        assertThat(model.windows).hasSize(5) // none after the one that threw
    }

    @Test
    fun `after a reset an identical capture gives an identical result, and the model is reset too`() {
        val random = Random(2)
        val frames = Array(200) { FloatArray(frame) { (random.nextGaussian() * 0.02).toFloat() } }
        val model = StubModel { if (it % 40 < 3) 0.99f else 0f }
        val energy = Scripted(began = 16)
        val layer = layer(energy, model)
        val first = run(layer, 200, samples = { frames[it - 1] })
        val armedFirst = layer.armedFrame
        energy.frames = 0
        layer.reset()
        assertThat(layer.stoppedBy).isNull()
        val second = run(layer, 200, samples = { frames[it - 1] })

        assertThat(second.toString()).isEqualTo(first.toString())
        assertThat(layer.armedFrame).isEqualTo(armedFirst)
        assertThat(model.resets).isEqualTo(2) // when built, and by the reset
    }

    @Test
    fun `a new layer starts the model from silence`() {
        val model = StubModel { 0f }
        layer(Scripted(), model)
        assertThat(model.resets).isEqualTo(1)
    }

    // ---- scenarios, with the real endpointer on synthetic levels ---------------------------------

    private fun newEndpointer() = Endpointer(leadInMillis = Endpointer.MIC_SETTLE_MILLIS)

    /** Speech at 0.05 on frames 41-115 over a floor of 0.003, then a clink of two frames every 15, from frame 130. */
    private fun backyard(): FloatArray {
        val rms = FloatArray(1_000) { 0.003f }
        rms.fill(0.05f, 40, 115)
        for (f in 130 until 1_000 step 15) {
            rms[f] = 0.05f
            if (f + 1 < 1_000) rms[f + 1] = 0.05f
        }
        return rms
    }

    private fun runEndpointer(model: VoiceModel?, rms: FloatArray) =
        run(VoiceCheckLayer(newEndpointer()::accept, model), rms = { rms[it - 1] })

    @Test
    fun `birds after the command hold the energy part open, and the voice check ends the capture`() {
        val rms = backyard()
        val alone = runEndpointer(null, rms)
        assertThat(alone.began).isEqualTo(41)

        val lastVoiced = (0 until 100).last { windowFrame(it) <= 115 }
        val model = StubModel { if (it <= lastVoiced) 0.97f else 0.01f }
        val layered = runEndpointer(model, rms)

        assertThat(layered.began).isEqualTo(41)
        assertThat(layered.stop).isEqualTo(windowFrame(lastVoiced + 25))
        assertThat(layered.reason).isEqualTo(StopReason.ENDPOINT)
        assertThat(layered.stoppedBy).isEqualTo(VoiceCheckLayer.StoppedBy.VOICE)
        assertThat(alone.stop ?: 1_000).isGreaterThan(layered.stop!!)
    }

    @Test
    fun `a television's voice after the command changes nothing`() {
        val rms = backyard()
        val alone = runEndpointer(null, rms)
        val model = StubModel { if (windowFrame(it) <= 115) 0.97f else 0.6f }
        val layered = runEndpointer(model, rms)

        assertThat(layered.stop).isEqualTo(alone.stop)
        assertThat(layered.reason).isEqualTo(alone.reason)
    }

    @Test
    fun `a pause of 24 quiet windows survives, 25 ends the capture`() {
        for ((quiet, ends) in listOf(24 to false, 25 to true)) {
            val ps = List(10) { 0.97f } + List(quiet) { 0.01f } + List(30) { 0.97f } + List(300) { 0.01f }
            val outcome = run(layer(Scripted(began = 16), StubModel { ps[it] }), 400)
            val firedInPause = outcome.stop != null && outcome.stop <= windowFrame(10 + quiet - 1)
            assertThat(firedInPause).isEqualTo(ends)
        }
    }
}
