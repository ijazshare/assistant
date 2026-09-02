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
import android.util.Log
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.services.MachineAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What was read from the screen, and whether the words came from pixels. */
data class ScreenText(val lines: List<String>, val fromPixels: Boolean)

/**
 * Reads whatever is on screen: the accessibility tree when it has words in it, and the
 * pixels when it does not.
 *
 * The tree is always tried first. It is instant, exact, and free of misreadings. Only
 * when it comes back too thin to have described the screen — a couple of button labels
 * over a photo, a video, a game — is a screenshot taken and read by the OCR model, and
 * only if that model has been downloaded.
 */
class ScreenReader(private val context: Context) {

    private val ocr = TesseractEngine(context)
    private val storage = ModelStorage(context)
    private val registry = ModelRegistry(context)

    val ocrAvailable: Boolean
        get() = registry.byRole(ModelRole.OCR).any { storage.quickState(it) == ModelState.Ready }

    suspend fun read(service: MachineAccessibilityService): ScreenText? {
        val lines = service.readScreenText()
        if (lines.size >= MIN_LINES && lines.sumOf { it.length } >= MIN_CHARS) {
            return ScreenText(lines, fromPixels = false)
        }

        val fromPixels = readPixels(service)
        return when {
            !fromPixels.isNullOrEmpty() -> ScreenText(fromPixels, fromPixels = true)
            lines.isNotEmpty() -> ScreenText(lines, fromPixels = false)
            else -> null
        }
    }

    private suspend fun readPixels(service: MachineAccessibilityService): List<String>? {
        val asset = registry.byRole(ModelRole.OCR)
            .firstOrNull { storage.quickState(it) == ModelState.Ready } ?: return null
        val shot = service.screenshot() ?: return null
        return withContext(Dispatchers.Default) {
            if (!ocr.load(storage.target(asset))) return@withContext null
            val started = System.nanoTime()
            val lines = ocr.read(shot)
            Log.i(TAG, "ocr: ${lines.size} lines in ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms")
            lines
        }
    }

    fun release() = ocr.release()

    private companion object {
        const val TAG = "TheMachine"

        // Below this the tree has described chrome, not content.
        const val MIN_LINES = 3
        const val MIN_CHARS = 40
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
