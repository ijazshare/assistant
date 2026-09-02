/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Turns a spoken name into a phone number.
 *
 * If the name is already a number it is used as-is, so messaging works even without
 * contacts access. Otherwise this queries the contacts provider, which returns
 * nothing rather than throwing when the permission is absent — so the caller must
 * treat "not found" as the answer, which it does.
 */
class ContactLookup(private val context: Context) {

    fun resolveNumber(spoken: String): String? {
        val trimmed = spoken.trim()
        if (trimmed.isEmpty()) return null
        if (looksLikeNumber(trimmed)) return trimmed.filter { it.isDigit() || it == '+' }
        if (!hasContactsPermission()) return null

        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            android.net.Uri.encode(trimmed),
        )
        return context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun looksLikeNumber(value: String): Boolean =
        value.count { it.isDigit() } >= MIN_DIGITS && value.none { it.isLetter() }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        /** Short enough for a shortcode, long enough not to match "7" in "call 7". */
        const val MIN_DIGITS = 3
    }
}
