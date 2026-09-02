/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A deliberately trivial test so the unit-test task is wired and exercised from the
 * first commit. Real coverage arrives with TimeResolver and the intent mapper in P4.
 */
class BuildSanityTest {
    @Test
    fun `application id matches the documented package`() {
        assertThat(BuildConfig.APPLICATION_ID).startsWith("io.github.hasanismail.themachine")
    }

    @Test
    fun `debug builds are flagged as debuggable`() {
        assertThat(BuildConfig.BUILD_TYPE).isAnyOf("debug", "release")
    }
}
