/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes an uncaught exception to a file on this device, and nowhere else.
 *
 * Not crash reporting: nothing is uploaded, there is no SDK, and the file never leaves
 * the phone unless its owner sends it. That distinction is the whole design — the
 * privacy invariants forbid telemetry, and this respects them while closing a real gap.
 *
 * The gap was found the hard way. The app crash-looped on a phone that was not the one
 * being developed against, and there was no way to learn why: logcat needs a cable and a
 * laptop, and the app itself kept no record. Whatever killed it was written down nowhere
 * a person could reach. A build that cannot say why it died is a build that can only be
 * debugged by whoever owns the right hardware.
 *
 * Native crashes — a SIGSEGV inside llama.cpp or whisper.cpp — do not come through here,
 * because the process is already gone. Those still need a tombstone. This catches
 * everything on the Kotlin side, which is most of what actually goes wrong.
 */
object CrashLog {

    /**
     * Installs the handler, keeping whatever was there before.
     *
     * The previous handler is always called: it is the one that shows the user "app has
     * stopped" and ends the process. Swallowing it would leave a wedged process, which is
     * worse than the crash.
     */
    fun install(context: Context) {
        val directory = directory(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(directory, thread, error) }
                .onFailure { Log.w(TAG, "could not record the crash", it) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Every recorded crash, newest first. */
    fun recent(context: Context): List<String> =
        directory(context).listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { runCatching { it.readText() }.getOrNull() }
            .orEmpty()

    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }

    private fun directory(context: Context): File =
        File(context.getExternalFilesDir(null), "crash").apply { mkdirs() }

    private fun write(directory: File, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("when:    " + LocalDateTime.now().format(STAMP))
            appendLine("thread:  " + thread.name)
            appendLine("device:  ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            appendLine("abi:     " + Build.SUPPORTED_ABIS.joinToString(","))
            appendLine()
            append(stack)
        }
        File(directory, "crash_" + LocalDateTime.now().format(FILE_STAMP) + ".txt").writeText(report)

        // Oldest first out. A crash loop would otherwise fill the card with the same
        // stack trace a few thousand times.
        directory.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }

    private const val TAG = "TheMachine"
    private const val KEEP = 10
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
}
