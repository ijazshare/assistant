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

/**
 * Holds the one model the session loads lazily, so that loading it and releasing it can overlap.
 *
 * Loading is slow and runs on a background thread; [release] runs when the overlay goes away,
 * whenever that is. A model that finishes loading after a release would never be freed, so each
 * load notes which generation of the slot it started in, and one that finds the slot released
 * meanwhile frees what it loaded instead of keeping it.
 */
internal class VoiceModelSlot<M : Any>(private val free: (M) -> Unit) {
    private val lock = Any()
    private var model: M? = null
    private var rejected = false
    private var generation = 0

    /** The loaded model, or null if there is none or it was refused. */
    fun current(): M? = synchronized(lock) { model }

    /**
     * Loads the model once. [open] returns it, or null if it is not usable, which is remembered so it
     * is not tried again. It runs outside the lock and may take as long as it likes.
     */
    fun prepare(open: () -> M?) {
        val started = synchronized(lock) {
            if (model != null || rejected) return
            generation
        }
        val opened = open()
        val surplus = synchronized(lock) {
            when {
                // Released while it loaded, or loaded twice by two summons at once.
                generation != started || model != null -> opened

                opened == null -> null.also { rejected = true }

                else -> null.also { model = opened }
            }
        }
        surplus?.let(free)
    }

    /** Frees the model, and any load in flight will free its own. The slot can be used again. */
    fun release() {
        val old = synchronized(lock) {
            generation++
            model.also { model = null }
        }
        old?.let(free)
    }
}
