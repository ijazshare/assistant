/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.wake

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Listens for "hey root" and nothing else.
 *
 * A keyword spotter rather than a transcriber: it is a 3.3M-parameter streaming model
 * that answers one question about each chunk of audio, which is what makes it cheap
 * enough to leave running. Whisper cannot do this job — it pads every call to a
 * thirty-second window, so a detector polling once a second would pay for thirty seconds
 * of encoding every second, forever.
 *
 * The phrase is open-vocabulary: the model was never trained on "hey root" specifically,
 * and the phrase is supplied as the word-pieces it decomposes into. Those pieces are
 * fixed at build time because the shipped library has no tokeniser in it — it looks
 * pieces up in a table and nothing more.
 */
class WakeWordEngine(private val directory: File) {

    private val lock = Any()
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    val isLoaded: Boolean get() = spotter != null

    /**
     * Loads the spotter from an unpacked model directory.
     *
     * The int8 encoder is chosen deliberately: this runs for hours, and the full-precision
     * one is two and a half times the size for a decision that is a yes or a no.
     */
    fun load(): Boolean = synchronized(lock) {
        if (spotter != null) return@synchronized true

        val home = directory.walkTopDown().maxDepth(WALK_DEPTH)
            .firstOrNull { it.isDirectory && File(it, TOKENS).isFile }
            ?: directory.takeIf { File(it, TOKENS).isFile }
        if (home == null) {
            Log.e(TAG, "wake: no model under ${directory.absolutePath}")
            return@synchronized false
        }

        val encoder = pick(home, "encoder", preferInt8 = true) ?: return@synchronized false
        val decoder = pick(home, "decoder", preferInt8 = false) ?: return@synchronized false
        val joiner = pick(home, "joiner", preferInt8 = true) ?: return@synchronized false
        val keywords = writeKeywords(home)

        val created = runCatching {
            KeywordSpotter(
                assetManager = null,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = encoder.absolutePath,
                            decoder = decoder.absolutePath,
                            joiner = joiner.absolutePath,
                        ),
                        tokens = File(home, TOKENS).absolutePath,
                        numThreads = THREADS,
                        modelType = MODEL_TYPE,
                    ),
                    keywordsFile = keywords.absolutePath,
                    keywordsScore = KEYWORD_BOOST,
                    keywordsThreshold = KEYWORD_THRESHOLD,
                ),
            )
        }.onFailure { Log.e(TAG, "wake: could not load the spotter", it) }.getOrNull()
            ?: return@synchronized false

        spotter = created
        stream = created.createStream()
        Log.i(TAG, "wake: listening for \"$SPOKEN\"")
        true
    }

    /**
     * Feeds one chunk of audio and reports whether the phrase was heard in it.
     *
     * The stream is reset on a hit so the same utterance cannot fire twice.
     */
    fun accept(samples: FloatArray): Boolean = synchronized(lock) {
        val spotter = this.spotter ?: return@synchronized false
        val stream = this.stream ?: return@synchronized false
        runCatching {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            while (spotter.isReady(stream)) spotter.decode(stream)
            val heard = spotter.getResult(stream).keyword
            if (heard.isNotBlank()) {
                spotter.reset(stream)
                Log.i(TAG, "wake: heard [$heard]")
                true
            } else {
                false
            }
        }.onFailure { Log.e(TAG, "wake: decode failed", it) }.getOrDefault(false)
    }

    /**
     * Throws away what has been heard so far without unloading anything.
     *
     * Used when the assistant takes over: whatever was part-way through the decoder when
     * the microphone changed hands is not worth carrying into the next phrase.
     */
    fun reset() {
        synchronized(lock) {
            val spotter = this.spotter ?: return
            val stream = this.stream ?: return
            runCatching { spotter.reset(stream) }
        }
    }

    fun release() {
        synchronized(lock) {
            stream?.runCatching { release() }
            stream = null
            spotter?.runCatching { release() }
            spotter = null
        }
    }

    /** The quantised file when there is one, since this runs for hours. */
    private fun pick(home: File, part: String, preferInt8: Boolean): File? {
        val candidates = home.listFiles()?.filter { it.name.startsWith(part) && it.extension == "onnx" }
        val chosen = candidates
            ?.sortedByDescending { it.name.contains("int8") == preferInt8 }
            ?.firstOrNull()
        if (chosen == null) Log.e(TAG, "wake: no $part model in ${home.name}")
        return chosen
    }

    /**
     * Writes the phrase out in the form the spotter reads.
     *
     * The pieces are hard-coded because the shipped library cannot produce them: its
     * keyword path looks each piece up in the model's own token table and has no
     * tokeniser to fall back on. Each of these was checked against that table —
     * HE=49, Y=17, RO=208, O=22, T=4 — and the shape matches the vendor's own file,
     * where "HEY SIRI" is written the same way.
     *
     * Pieces and nothing else. The format allows a per-phrase score, threshold and
     * display name, but every field is whitespace-separated and so is a display name:
     * writing "@HEY ROOT" made the parser look for a token called ROOT and refuse to
     * start at all. Score and threshold are set on the config instead, where they
     * apply to every phrase and cannot be mistaken for one.
     */
    private fun writeKeywords(home: File): File {
        val file = File(home, "hey-root.txt")
        val line = PIECES + "\n"
        if (!file.isFile || file.readText() != line) file.writeText(line)
        return file
    }

    companion object {
        private const val TAG = "TheMachine"
        const val SAMPLE_RATE = 16_000
        const val SPOKEN = "HEY ROOT"

        /** The word-pieces of the phrase, in the model's own vocabulary. */
        private const val PIECES = "▁HE Y ▁RO O T"

        private const val TOKENS = "tokens.txt"
        private const val MODEL_TYPE = "zipformer2"
        private const val FEATURE_DIM = 80
        private const val THREADS = 1
        private const val WALK_DEPTH = 3

        /**
         * How hard to push the decoder towards the phrase, and how sure it must be.
         *
         * The threshold is the interesting one: too low and the assistant wakes up in
         * conversation, too high and it ignores its own name. The vendor's default is
         * 0.25; this is a little stricter, because a false wake opens a microphone.
         */
        private const val KEYWORD_BOOST = 2.0f
        private const val KEYWORD_THRESHOLD = 0.35f
    }
}
