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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.services.MachineAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The OCR engine on real pixels.
 *
 * The image is drawn rather than photographed, because what this has to read is a phone
 * screen: crisp text on a flat background, which is the easy case, and the one that
 * matters when the accessibility tree has come back empty.
 */
@RunWith(AndroidJUnit4::class)
class TesseractEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = ModelStorage(context)

    private fun traineddata() = ModelRegistry(context).byRole(ModelRole.OCR)
        .firstOrNull { storage.quickState(it) == ModelState.Ready }
        ?.let { storage.target(it) }

    private fun rendered(vararg lines: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
        }
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            lines.forEachIndexed { index, line ->
                drawText(line, MARGIN, MARGIN + TEXT_SIZE * (index + 1), paint)
            }
        }
        return bitmap
    }

    @Test
    fun readsRenderedText() {
        val file = traineddata()
        assumeTrue("No OCR model installed", file != null)
        val engine = TesseractEngine(context)
        try {
            assertThat(engine.load(file!!)).isTrue()
            // Loading twice must be free, not a second four-megabyte copy.
            assertThat(engine.load(file)).isTrue()

            val started = System.nanoTime()
            val lines = engine.read(rendered("Timer set for 10 minutes", "Alarm at 7:00 AM"))
            Log.i(TAG, "OCR ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms -> $lines")

            val text = lines.joinToString(" ")
            assertThat(text).contains("Timer")
            assertThat(text).contains("10")
            assertThat(text).contains("minutes")
            assertThat(text).contains("Alarm")
        } finally {
            engine.release()
        }
    }

    @Test
    fun anEmptyImageReadsAsNothing() {
        val file = traineddata()
        assumeTrue("No OCR model installed", file != null)
        val engine = TesseractEngine(context)
        try {
            assertThat(engine.load(file!!)).isTrue()
            assertThat(engine.read(rendered())).isEmpty()
        } finally {
            engine.release()
        }
    }

    @Test
    fun nothingIsReadWithoutAModel() {
        val engine = TesseractEngine(context)
        assertThat(engine.isLoaded).isFalse()
        assertThat(engine.read(rendered("anything"))).isEmpty()
        engine.release()
    }

    @Test
    fun aScreenshotCanBeTakenWhenTheServiceIsOn() {
        val service = MachineAccessibilityService.connected()
        assumeTrue("Accessibility service not enabled", service != null)
        val shot = runBlocking { service!!.screenshot() }
        assertThat(shot).isNotNull()
        Log.i(TAG, "SCREENSHOT ${shot!!.width}x${shot.height}")
        assertThat(shot.width).isGreaterThan(0)
        assertThat(shot.height).isGreaterThan(0)
    }

    private companion object {
        const val TAG = "TheMachine"
        const val WIDTH = 1000
        const val HEIGHT = 260
        const val MARGIN = 40f
        const val TEXT_SIZE = 64f
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
