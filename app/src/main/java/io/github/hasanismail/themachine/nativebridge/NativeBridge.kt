/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.nativebridge

import android.content.Context
import android.util.Log

/**
 * The single point where `libthemachine.so` is loaded and ggml's dynamically
 * dispatched CPU backends are registered.
 *
 * `WhisperEngine` (P3) and `LlamaEngine` (P4) will sit on top of this rather
 * than each loading the library themselves — ggml's backend registry is process
 * global, so initialising it in one place keeps the ordering honest.
 */
object NativeBridge {

    private const val TAG = "TheMachine"
    private const val LIBRARY = "themachine"

    /** Null until [load] has been called; non-null holds the failure reason. */
    @Volatile
    private var loadError: String? = null

    @Volatile
    private var loaded = false

    @Volatile
    private var backendCount = 0

    /**
     * Loads the native library and registers ggml's CPU backends. Safe to call
     * repeatedly and from any thread; only the first call does work.
     *
     * Returns false if the library could not be loaded, which on a 16 KB page
     * device is the symptom of an unaligned `.so`.
     */
    @Synchronized
    fun load(context: Context): Boolean {
        if (loaded) return true
        if (loadError != null) return false
        return try {
            System.loadLibrary(LIBRARY)
            // With extractNativeLibs=false this directory may not hold real files,
            // in which case the native side falls back to dlopen by soname.
            backendCount = nativeInit(context.applicationInfo.nativeLibraryDir)
            loaded = true
            Log.i(TAG, "native bridge loaded; $backendCount ggml backend(s) registered")
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message ?: e.toString()
            Log.e(TAG, "failed to load lib$LIBRARY.so", e)
            false
        }
    }

    /** A snapshot of what the native side reports, for the debug screen. */
    fun buildInfo(context: Context): NativeBuildInfo {
        if (!load(context)) {
            return NativeBuildInfo.unavailable(loadError ?: "unknown error")
        }
        return NativeBuildInfo(
            whisperVersion = nativeWhisperVersion(),
            llamaVersion = nativeLlamaVersion(),
            whisperSystemInfo = nativeWhisperSystemInfo(),
            llamaSystemInfo = nativeLlamaSystemInfo(),
            backendReport = nativeBackendReport(),
            backendCount = backendCount,
            supportsMmap = nativeSupportsMmap(),
            pageSizeBytes = nativePageSize(),
            loadError = null,
        )
    }

    private external fun nativeInit(libraryDir: String): Int

    private external fun nativeWhisperVersion(): String

    private external fun nativeLlamaVersion(): String

    private external fun nativeWhisperSystemInfo(): String

    private external fun nativeLlamaSystemInfo(): String

    private external fun nativeBackendReport(): String

    private external fun nativeSupportsMmap(): Boolean

    private external fun nativePageSize(): Long
}

/**
 * What the native layer reports about itself. [loadError] non-null means nothing
 * else in here is meaningful.
 */
data class NativeBuildInfo(
    val whisperVersion: String,
    val llamaVersion: String,
    val whisperSystemInfo: String,
    val llamaSystemInfo: String,
    val backendReport: String,
    val backendCount: Int,
    val supportsMmap: Boolean,
    val pageSizeBytes: Long,
    val loadError: String?,
) {
    /** A 16 KB device refuses to load a 4 KB-aligned library outright. */
    val is16KbPageDevice: Boolean get() = pageSizeBytes >= SIXTEEN_KB

    companion object {
        private const val SIXTEEN_KB = 16 * 1024L

        fun unavailable(error: String) = NativeBuildInfo(
            whisperVersion = "",
            llamaVersion = "",
            whisperSystemInfo = "",
            llamaSystemInfo = "",
            backendReport = "",
            backendCount = 0,
            supportsMmap = false,
            pageSizeBytes = 0,
            loadError = error,
        )
    }
}
