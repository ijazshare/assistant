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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File

/**
 * Unpacks a downloaded archive.
 *
 * Only the voice needs this: it arrives as a tarball because the phonemiser's data is
 * several hundred small files, which is a poor thing to fetch one at a time and a worse
 * thing to leave half-fetched.
 */
object ModelArchive {

    private const val TAG = "TheMachine"

    /**
     * Extracts [archive] into [into], returning true if the contents are there afterwards.
     *
     * Extraction goes to a scratch directory that is only moved into place once it has
     * finished, so an interrupted unpack cannot leave behind a directory that looks
     * complete. An archive already unpacked is left alone.
     */
    fun unpack(archive: File, into: File): Boolean {
        if (into.isDirectory && (into.list()?.isNotEmpty() == true)) return true
        if (!archive.isFile) return false

        val scratch = File(into.parentFile, into.name + ".unpacking")
        scratch.deleteRecursively()
        if (!scratch.mkdirs()) {
            Log.e(TAG, "archive: cannot create ${scratch.name}")
            return false
        }

        return runCatching {
            BZip2CompressorInputStream(archive.inputStream().buffered()).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        write(tar, scratch, entry.name, entry.isDirectory)
                        entry = tar.nextEntry
                    }
                }
            }
            into.deleteRecursively()
            check(scratch.renameTo(into)) { "could not move ${scratch.name} into place" }
            Log.i(TAG, "archive: unpacked ${archive.name}")
            true
        }.getOrElse { failure ->
            Log.e(TAG, "archive: failed to unpack ${archive.name}", failure)
            scratch.deleteRecursively()
            false
        }
    }

    /**
     * Writes one entry, refusing any whose path escapes the destination.
     *
     * A tar entry may name "../" and archives are downloaded from the network, so this
     * is checked rather than assumed even though the source is one we chose.
     */
    private fun write(tar: TarArchiveInputStream, root: File, name: String, isDirectory: Boolean) {
        val out = File(root, name).canonicalFile
        val prefix = root.canonicalPath + File.separator
        require(out.path.startsWith(prefix)) { "archive entry escapes its directory: $name" }

        if (isDirectory) {
            out.mkdirs()
            return
        }
        out.parentFile?.mkdirs()
        out.outputStream().buffered().use { tar.copyTo(it) }
    }
}
