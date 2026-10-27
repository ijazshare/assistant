/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceModelSlotTest {

    private val freed = ArrayList<String>()
    private val slot = VoiceModelSlot<String> { freed.add(it) }

    @Test
    fun `a model is loaded once and then served`() {
        var opens = 0
        slot.prepare { "a".also { opens++ } }
        slot.prepare { "b".also { opens++ } }

        assertThat(slot.current()).isEqualTo("a")
        assertThat(opens).isEqualTo(1)
        assertThat(freed).isEmpty()
    }

    @Test
    fun `a refused model is not tried again`() {
        var opens = 0
        slot.prepare { null.also { opens++ } }
        slot.prepare { "late".also { opens++ } }

        assertThat(slot.current()).isNull()
        assertThat(opens).isEqualTo(1)
    }

    @Test
    fun `a model that finishes loading after the release is freed at once, not leaked`() {
        // The overlay goes away while the first summon is still hashing and loading.
        slot.prepare {
            slot.release()
            "late"
        }

        assertThat(slot.current()).isNull()
        assertThat(freed).containsExactly("late")
    }

    @Test
    fun `release frees the loaded model, and the slot loads again afterwards`() {
        slot.prepare { "first" }
        slot.release()
        slot.release()
        assertThat(slot.current()).isNull()
        assertThat(freed).containsExactly("first")

        slot.prepare { "second" }
        assertThat(slot.current()).isEqualTo("second")
    }

    @Test
    fun `two summons loading at once keep one model and free the other`() {
        slot.prepare {
            slot.prepare { "inner" }
            "outer"
        }

        assertThat(slot.current()).isEqualTo("inner")
        assertThat(freed).containsExactly("outer")
    }

    @Test
    fun `a load that was refused after a release does not mark the next one refused`() {
        slot.prepare {
            slot.release()
            null
        }
        slot.prepare { "next" }

        assertThat(slot.current()).isEqualTo("next")
    }
}
