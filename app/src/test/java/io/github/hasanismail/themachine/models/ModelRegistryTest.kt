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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guards the shipped registry itself. A typo in a URL or a truncated hash would
 * otherwise only surface as a failed download on someone's phone.
 *
 * Reads the real asset file off disk rather than through a Context: the registry is
 * plain JSON parsing, and keeping the test free of an Android runtime makes it fast and
 * removes a dependency that does not load reliably on every host.
 */
class ModelRegistryTest {

    /** Roughly a gigabyte: enough for the 1B language model, far short of the 4B. */
    private val firstRunCeiling = 1_500_000_000L

    private val registry = ModelRegistry(
        File("src/main/assets/" + ModelRegistry.REGISTRY_PATH).readText(),
    )

    @Test
    fun `registry parses and is not empty`() {
        assertThat(registry.version).isEqualTo(1)
        assertThat(registry.all).isNotEmpty()
    }

    @Test
    fun `every asset has a well formed sha256`() {
        for (asset in registry.all) {
            assertThat(asset.sha256).matches("[0-9a-f]{64}")
        }
    }

    @Test
    fun `every asset has an https url ending in its file name`() {
        for (asset in registry.all) {
            // http would mean a model could be swapped in transit; the checksum would
            // catch it, but there is no reason to allow it in the first place.
            assertThat(asset.url).startsWith("https://")
            assertThat(asset.url).endsWith(asset.fileName)
        }
    }

    @Test
    fun `every asset declares a plausible size and ram requirement`() {
        for (asset in registry.all) {
            assertThat(asset.byteSize).isGreaterThan(0)
            assertThat(asset.minRamMb).isGreaterThan(0)
        }
    }

    @Test
    fun `asset ids are unique`() {
        val ids = registry.all.map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `exactly one default per role`() {
        // The first run downloads the defaults; two defaults for one role would fetch a
        // gigabyte the user never asked for, and none would leave the pipeline unable
        // to start.
        for (role in ModelRole.entries) {
            val defaults = registry.byRole(role).filter { it.isDefault }
            assertThat(defaults).hasSize(1)
        }
    }

    @Test
    fun `archive assets declare an extracted size and plain ones do not`() {
        for (asset in registry.all) {
            if (asset.archive == ArchiveFormat.NONE) {
                assertThat(asset.installFootprintBytes).isEqualTo(asset.byteSize)
            } else {
                // Otherwise the storage preflight under-counts and the unpack runs the
                // volume out of space after the download has already succeeded.
                assertThat(asset.extractedByteSize).isGreaterThan(0)
                assertThat(asset.installFootprintBytes).isGreaterThan(asset.byteSize)
            }
        }
    }

    @Test
    fun `no role defaults to a model too large for a first run`() {
        // A first run that pulls the 3 GB model by mistake is a bad first run. This was
        // once written as "the default is the smallest", which stopped being true the
        // moment a smaller experimental model was listed alongside the working one:
        // smallest is not the property that matters, affordable-on-first-run is.
        for (role in ModelRole.entries) {
            val assets = registry.byRole(role)
            if (assets.isEmpty()) continue
            val default = assets.single { it.isDefault }
            assertThat(default.byteSize).isAtMost(firstRunCeiling)
        }
    }

    @Test
    fun `every role has exactly one default`() {
        for (role in ModelRole.entries) {
            val assets = registry.byRole(role)
            if (assets.isEmpty()) continue
            assertThat(assets.count { it.isDefault }).isEqualTo(1)
        }
    }
}
