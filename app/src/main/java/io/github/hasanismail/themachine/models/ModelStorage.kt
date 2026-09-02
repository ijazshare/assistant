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

import android.content.Context
import android.os.StatFs
import java.io.File
import java.security.MessageDigest

/** Where an asset stands right now. */
sealed interface ModelState {
    /** Not on disk, and nothing in progress. */
    data object Absent : ModelState

    /** A partial file exists; the next attempt resumes from [downloadedBytes]. */
    data class Partial(val downloadedBytes: Long) : ModelState

    /** Downloading now. [downloadedBytes] of [totalBytes]. */
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelState

    /** Bytes are on disk and hashed correctly. */
    data object Ready : ModelState

    /** On disk but the hash did not match; the file is unusable and should be re-fetched. */
    data class Corrupt(val reason: String) : ModelState
}

/**
 * Owns the models directory: where files live, how much room is left, and whether what
 * is on disk can be trusted.
 *
 * Models sit in getExternalFilesDir(null)/models — app-private, so no storage permission
 * is needed, and removed when the app is uninstalled rather than left behind as a
 * gigabyte of orphaned data.
 */
class ModelStorage(private val rootDir: File) {

    /**
     * App-private external storage, so no storage permission is needed and the models
     * are removed on uninstall rather than left behind as a gigabyte of orphaned data.
     */
    constructor(context: Context) : this(File(context.getExternalFilesDir(null), "models"))

    val root: File
        get() = rootDir.apply { mkdirs() }

    fun target(asset: ModelAsset): File = File(root, asset.fileName)

    /** In-progress downloads get a suffix so a half-file is never mistaken for a model. */
    fun partial(asset: ModelAsset): File = File(root, asset.fileName + PARTIAL_SUFFIX)

    /** Where a TAR_BZ2 asset is unpacked to. */
    fun extractedDir(asset: ModelAsset): File =
        File(root, asset.fileName.substringBefore(".tar"))

    /**
     * Falls back to File.usableSpace when StatFs is unavailable — StatFs is an Android
     * class, and this lets the storage logic be exercised by a plain JVM test.
     *
     * Lint suggests StorageManager.getAllocatableBytes, which counts space the system
     * could free by clearing caches. That is the right call for an app that can ask the
     * system to make room; here the number is shown to the user as "free space" and
     * used for a preflight, where counting reclaimable cache as available would promise
     * room that may not materialise.
     */
    @android.annotation.SuppressLint("UsableSpace")
    fun freeBytes(): Long = runCatching {
        val stat = StatFs(root.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrElse { root.usableSpace }

    /**
     * True when there is room for the asset plus a margin. The margin matters: filling
     * the volume to the last byte tends to break other things on the phone before it
     * breaks this download.
     */
    fun hasRoomFor(asset: ModelAsset): Boolean =
        freeBytes() > asset.installFootprintBytes + HEADROOM_BYTES

    /**
     * Cheap state, from file existence and size only. A full hash is deliberately not
     * run here — this is called to paint a list, and hashing a gigabyte to draw a row
     * would make the screen janky.
     */
    fun quickState(asset: ModelAsset): ModelState {
        val done = target(asset)
        if (done.isFile) {
            return if (done.length() == asset.byteSize) {
                ModelState.Ready
            } else {
                ModelState.Corrupt("expected ${asset.byteSize} bytes, found ${done.length()}")
            }
        }
        val partial = partial(asset)
        if (partial.isFile && partial.length() > 0) return ModelState.Partial(partial.length())
        return ModelState.Absent
    }

    /**
     * The real check: streams the file and compares the digest. Slow by nature — only
     * called right after a download completes, never to render UI.
     */
    fun verify(asset: ModelAsset, file: File = target(asset)): Boolean {
        if (!file.isFile || file.length() != asset.byteSize) return false
        return sha256(file).equals(asset.sha256, ignoreCase = true)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Removes the asset and anything unpacked from it. */
    fun delete(asset: ModelAsset) {
        target(asset).delete()
        partial(asset).delete()
        extractedDir(asset).deleteRecursively()
    }

    fun bytesOnDisk(asset: ModelAsset): Long {
        val file = target(asset)
        val partial = partial(asset)
        return when {
            file.isFile -> file.length()
            partial.isFile -> partial.length()
            else -> 0
        }
    }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
        const val BUFFER_BYTES = 1 shl 16

        /** Leave a quarter of a gigabyte so the phone stays usable. */
        const val HEADROOM_BYTES = 256L * 1024 * 1024
    }
}
