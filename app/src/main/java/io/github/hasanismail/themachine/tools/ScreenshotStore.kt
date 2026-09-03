/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import io.github.hasanismail.themachine.services.MachineAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Puts a picture of the screen where the user will actually find it.
 *
 * Through MediaStore rather than `getExternalFilesDir`: app-private external files are
 * not indexed, so a screenshot saved there would never appear in the gallery, which is
 * the whole point of asking for one. Inserting into MediaStore needs no storage
 * permission at all — an app may always create and write media it owns.
 *
 * This is the one place the assistant writes something other apps can read, and it
 * happens only when explicitly asked. Everything the screen reader captures stays in
 * memory and is never written down.
 */
class ScreenshotStore(private val context: Context) {

    /**
     * Captures the foreground window and saves it as a PNG.
     *
     * The capture is the same one the screen reader uses, which takes the window in
     * front rather than the display, so the assistant's own panel is not in the picture.
     */
    suspend fun save(service: MachineAccessibilityService): ToolResult {
        val bitmap = service.screenshot()
            ?: return ToolResult.failed(
                "I could not take a screenshot.",
                "Some apps, banking ones especially, refuse to be captured.",
            )
        return withContext(Dispatchers.IO) { write(bitmap) }
    }

    private fun write(bitmap: Bitmap): ToolResult {
        val name = "Machine_" + LocalDateTime.now().format(FILE_STAMP) + ".png"
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_DCIM + File.separator + Environment.DIRECTORY_SCREENSHOTS,
            )
            // Marked pending while it is written, so the gallery never shows a half-file.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            pending,
        ) ?: return ToolResult.failed("I could not save the screenshot.")

        val written = runCatching {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        }.onFailure { Log.w(TAG, "screenshot write failed", it) }.getOrNull() == true
        bitmap.recycle()

        if (!written) {
            resolver.delete(uri, null, null)
            return ToolResult.failed("I could not save the screenshot.")
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        Log.i(TAG, "screenshot saved as $name")
        return ToolResult.ok("Screenshot saved.", "DCIM/Screenshots/$name")
    }

    private companion object {
        const val TAG = "TheMachine"

        /** Screenshots are text, so PNG at full quality rather than JPEG. */
        const val PNG_QUALITY = 100

        /** Sortable and filename-safe, matching how the system names its own captures. */
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
