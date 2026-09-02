/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Reads text out of an image, offline, with Tesseract.
 *
 * This is the specialist the router turns to when a screen's accessibility tree has
 * nothing to say — a photo, a video frame, a game, a canvas — and the words exist only
 * as pixels. It is not a language model and is not asked to understand anything; it is
 * asked what the letters are, which is the one thing it is for.
 */
class TesseractEngine(private val context: Context) {

    private var api: TessBaseAPI? = null

    val isLoaded: Boolean get() = api != null

    /**
     * Loads the English model from a downloaded traineddata file.
     *
     * Tesseract insists on a `tessdata/` directory under the path it is given, so the
     * file is copied into one the first time — four megabytes, once.
     */
    fun load(traineddata: File): Boolean {
        synchronized(this) {
            if (api != null) return true
            if (!traineddata.isFile) return false

            val home = File(context.filesDir, "ocr")
            val target = File(File(home, "tessdata").apply { mkdirs() }, traineddata.name)
            if (!target.isFile || target.length() != traineddata.length()) {
                traineddata.copyTo(target, overwrite = true)
            }

            val tess = TessBaseAPI()
            val ok = runCatching {
                tess.init(home.absolutePath, LANGUAGE, TessBaseAPI.OEM_LSTM_ONLY)
            }.onFailure { Log.e(TAG, "ocr: init threw", it) }.getOrDefault(false)
            if (!ok) {
                tess.recycle()
                Log.e(TAG, "ocr: could not initialise from ${home.absolutePath}")
                return false
            }
            api = tess
            Log.i(TAG, "ocr: loaded ${traineddata.name}")
            return true
        }
    }

    /** Every line of text found in [bitmap], top to bottom, blank lines dropped. */
    fun read(bitmap: Bitmap): List<String> {
        // A hardware bitmap has no pixels this process can read; a screenshot is one.
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        return synchronized(this) {
            val tess = api ?: return@synchronized emptyList()
            runCatching {
                tess.setImage(software)
                val text = tess.getUTF8Text().orEmpty()
                tess.clear()
                text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            }.onFailure { Log.e(TAG, "ocr: recognition failed", it) }.getOrDefault(emptyList())
        }
    }

    fun release() {
        synchronized(this) {
            api?.recycle()
            api = null
        }
    }

    private companion object {
        const val TAG = "TheMachine"
        const val LANGUAGE = "eng"
    }
}
