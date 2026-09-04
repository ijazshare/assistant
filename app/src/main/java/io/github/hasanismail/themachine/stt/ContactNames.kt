/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * The contact names to bias speech recognition toward.
 *
 * Whisper prefers real English words, so an unusual name is transcribed as the nearest one
 * — "Hasan" becomes "Hudson" — and the wrong contact, or none, is chosen. Seeding the
 * decoder with the actual names makes them candidates. The list is capped and ordered by
 * how often each contact is reached: too long a prompt slows decoding and dilutes the bias,
 * and the people spoken to most are the ones worth biasing for.
 */
class ContactNames(private val context: Context) {

    // TIMES_CONTACTED is deprecated but still populated, and it is the only cheap signal for
    // "who is spoken to most" — exactly the names worth biasing for. No replacement exists.
    @Suppress("DEPRECATION")
    fun forBias(limit: Int = MAX_NAMES): String {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return ""

        val names = LinkedHashSet<String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null,
                null,
                "${ContactsContract.Contacts.TIMES_CONTACTED} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext() && names.size < limit) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    // A name, not a company or a raw number saved as a contact.
                    if (name.isNotEmpty() && name.none { it.isDigit() }) names.add(name)
                }
            }
        }
        return names.joinToString(", ")
    }

    private companion object {
        /** Enough to cover the people actually spoken to, short enough not to slow decoding. */
        const val MAX_NAMES = 64
    }
}
