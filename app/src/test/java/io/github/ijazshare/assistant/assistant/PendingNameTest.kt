/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PendingNameTest {

    @Test
    fun `the name in an answer is what follows the lead-in`() {
        assertThat(PendingName.nameIn("No, I meant Farid Noor.")).isEqualTo("farid noor")
        assertThat(PendingName.nameIn("It's Zubair")).isEqualTo("zubair")
        assertThat(PendingName.nameIn("Farid")).isEqualTo("farid")
    }

    @Test
    fun `a bare no names nobody`() {
        assertThat(PendingName.nameIn("No.")).isEmpty()
    }
}
