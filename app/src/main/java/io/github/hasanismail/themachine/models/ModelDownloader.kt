/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.models

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Progress callback: bytes so far, total expected. */
typealias ProgressListener = (downloaded: Long, total: Long) -> Unit

/** Why a download stopped. */
sealed interface DownloadResult {
    data object Success : DownloadResult
    data class Failed(val reason: String, val retryable: Boolean) : DownloadResult
    data object Cancelled : DownloadResult
}

/**
 * Fetches a model, resuming where it left off.
 *
 * Resume is not a nicety here. The default LLM is a gigabyte, and Android 15 onward
 * gives a dataSync foreground service only a few hours of runtime per day before it is
 * killed — so a download has to survive being stopped and restarted, repeatedly, rather
 * than starting over each time.
 */
class ModelDownloader(private val storage: ModelStorage) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Generous: a gigabyte over a slow connection is legitimately a long read, and
        // the per-read watchdog below catches a genuinely stalled socket faster.
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun download(
        asset: ModelAsset,
        onProgress: ProgressListener = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (storage.quickState(asset) == ModelState.Ready && storage.verify(asset)) {
            return@withContext DownloadResult.Success
        }
        if (!storage.hasRoomFor(asset)) {
            val needed = asset.installFootprintBytes / MB
            val free = storage.freeBytes() / MB
            return@withContext DownloadResult.Failed(
                "Needs about $needed MB free, only $free MB available.",
                retryable = false,
            )
        }

        val partial = storage.partial(asset)
        var alreadyHave = if (partial.isFile) partial.length() else 0L

        // A partial larger than the finished file means the file on the server changed
        // or the earlier attempt was writing something else. Start clean rather than
        // resuming into a mismatch that only surfaces as a checksum failure at the end.
        if (alreadyHave > asset.byteSize) {
            partial.delete()
            alreadyHave = 0
        }

        try {
            val result = fetch(asset, partial, alreadyHave, onProgress)
            if (result != null) return@withContext result

            if (!storage.verify(asset, partial)) {
                val actual = storage.sha256(partial)
                partial.delete()
                return@withContext DownloadResult.Failed(
                    "Checksum mismatch — expected ${asset.sha256.take(HASH_PREVIEW)}…, " +
                        "got ${actual.take(HASH_PREVIEW)}…",
                    // Deleting and starting over is worth one shot; a repeat means the
                    // source really has changed and the registry needs updating.
                    retryable = true,
                )
            }

            if (!partial.renameTo(storage.target(asset))) {
                return@withContext DownloadResult.Failed("Could not move the verified file into place.", true)
            }
            DownloadResult.Success
        } catch (e: CancellationException) {
            // Leave the partial file alone: that is the whole point of resuming.
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "download failed for ${asset.id}", e)
            DownloadResult.Failed(e.message ?: "Network error", retryable = true)
        }
    }

    /** Returns null on a clean finish, or the failure to report. */
    private suspend fun fetch(
        asset: ModelAsset,
        partial: File,
        alreadyHave: Long,
        onProgress: ProgressListener,
    ): DownloadResult? {
        val request = Request.Builder()
            .url(asset.url)
            .apply { if (alreadyHave > 0) header("Range", "bytes=$alreadyHave-") }
            .build()

        client.newCall(request).execute().use { response ->
            val mode = resumeMode(response.code, response.isSuccessful, partial)
            if (mode is ResumeMode.Refused) return mode.failure

            val appending = mode is ResumeMode.Append
            val body = response.body
            if (!appending) partial.delete()

            val startAt = if (appending) alreadyHave else 0L
            val written = stream(body.byteStream(), partial, startAt, asset.byteSize, onProgress)
            return when {
                written == null -> DownloadResult.Cancelled

                written != asset.byteSize -> DownloadResult.Failed(
                    "Truncated: got $written of ${asset.byteSize} bytes.",
                    retryable = true,
                )

                else -> null
            }
        }
    }

    private sealed interface ResumeMode {
        data object Append : ResumeMode
        data object Restart : ResumeMode
        data class Refused(val failure: DownloadResult.Failed) : ResumeMode
    }

    /**
     * 206 means the server honoured the Range and we append. 200 means it ignored the
     * Range and is sending the whole file, so the partial must be discarded — appending
     * to it would silently produce a corrupt file that only fails at the checksum.
     */
    private fun resumeMode(code: Int, successful: Boolean, partial: File): ResumeMode = when {
        code == HTTP_PARTIAL -> ResumeMode.Append

        successful -> ResumeMode.Restart

        code == HTTP_RANGE_NOT_SATISFIABLE -> {
            partial.delete()
            ResumeMode.Refused(
                DownloadResult.Failed("Server rejected the resume point; try again.", true),
            )
        }

        else -> ResumeMode.Refused(
            DownloadResult.Failed("Server returned $code", retryable = code >= HTTP_SERVER_ERROR),
        )
    }

    /** Copies the body to disk, reporting progress. Returns null if cancelled. */
    private suspend fun stream(
        input: java.io.InputStream,
        partial: File,
        startAt: Long,
        totalBytes: Long,
        onProgress: ProgressListener,
    ): Long? {
        val appending = startAt > 0
        val written = input.use { source ->
            java.io.FileOutputStream(partial, appending).use { output ->
                copyLoop(source, output, startAt, totalBytes, onProgress)
                    ?.also { output.fd.sync() }
            }
        } ?: return null
        onProgress(written, totalBytes)
        return written
    }

    /** The byte pump. Returns the new total, or null if the coroutine was cancelled. */
    private suspend fun copyLoop(
        source: java.io.InputStream,
        output: java.io.OutputStream,
        startAt: Long,
        totalBytes: Long,
        onProgress: ProgressListener,
    ): Long? {
        var written = startAt
        var sinceReport = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            if (!currentCoroutineContext().isActive) return null
            val read = source.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            written += read
            sinceReport += read
            if (sinceReport >= PROGRESS_INTERVAL_BYTES) {
                onProgress(written, totalBytes)
                sinceReport = 0
            }
        }
        return written
    }

    private companion object {
        const val TAG = "TheMachine"
        const val BUFFER_BYTES = 1 shl 16
        const val PROGRESS_INTERVAL_BYTES = 512L * 1024
        const val MB = 1024 * 1024
        const val HTTP_PARTIAL = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val HTTP_SERVER_ERROR = 500

        /** Enough hash to identify a mismatch in a message without filling the screen. */
        const val HASH_PREVIEW = 12
    }
}
