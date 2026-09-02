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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the JNI layer. Deliberately thin — it asserts the things that
 * silently degrade rather than crash, which is how every failure in P1 actually
 * presented itself.
 */
@RunWith(AndroidJUnit4::class)
class NativeBridgeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeLibraryLoads() {
        assertThat(NativeBridge.load(context)).isTrue()
    }

    @Test
    fun bothEnginesReportAVersion() {
        val info = NativeBridge.buildInfo(context)
        assertThat(info.loadError).isNull()
        // Versions come from whisper_version() and llama_version(), so a non-empty
        // value proves both libraries are linked in and callable, not merely present.
        assertThat(info.whisperVersion).isNotEmpty()
        assertThat(info.llamaVersion).isNotEmpty()
    }

    @Test
    fun aGgmlBackendIsRegistered() {
        val info = NativeBridge.buildInfo(context)
        // The failure this guards against: with libraries left inside the APK,
        // ggml's directory-scanning loader finds nothing, registers zero backends
        // and reports success. Inference would then have no backend to run on.
        assertThat(info.backendCount).isAtLeast(1)
        assertThat(info.backendReport).contains("CPU")
    }

    @Test
    fun cpuBackendAdvertisesArmAcceleration() {
        val info = NativeBridge.buildInfo(context)
        // GGML_CPU_ALL_VARIANTS should have scored and picked a variant matching the
        // device. If dispatch silently fell back to the armv8.0 baseline, NEON would
        // still be set but DOTPROD would not — and inference would be needlessly slow.
        assertThat(info.whisperSystemInfo).contains("NEON = 1")
        assertThat(info.whisperSystemInfo).contains("DOTPROD = 1")
    }

    @Test
    fun mmapIsSupported() {
        // Models are memory-mapped so a warm reload stays cheap; CLAUDE.md's latency
        // budget assumes it.
        assertThat(NativeBridge.buildInfo(context).supportsMmap).isTrue()
    }

    @Test
    fun pageSizeIsReported() {
        val pageSize = NativeBridge.buildInfo(context).pageSizeBytes
        assertThat(pageSize).isAtLeast(4096L)
        assertThat(java.lang.Long.bitCount(pageSize)).isEqualTo(1)
    }
}
