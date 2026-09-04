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
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Turns a spoken name into a phone number.
 *
 * If the name is already a number it is used as-is, so messaging works even without
 * contacts access. "me" resolves to the phone's own owner, never a contact. Otherwise it
 * queries the contacts provider — but the provider's filter is loose (it returned "MI
 * Aziz" for "me"), so a candidate is only accepted when its name genuinely matches. A
 * message sent to the wrong person is the worst outcome here; "not found" is far better.
 */
class ContactLookup(private val context: Context) {

    fun resolveNumber(spoken: String): String? {
        val trimmed = spoken.trim()
        return when {
            trimmed.isEmpty() -> null
            looksLikeNumber(trimmed) -> trimmed.filter { it.isDigit() || it == '+' }
            !hasContactsPermission() -> null
            ContactMatch.isSelf(trimmed) -> ownNumber()
            else -> lookupByName(trimmed)
        }
    }

    /**
     * The first candidate whose display name actually matches the spoken name. Fetching the
     * name alongside the number is the whole point: without it the provider's first row —
     * whatever it fuzzily matched — was taken on trust.
     */
    private fun lookupByName(name: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(name),
        )
        return context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(0) ?: continue
                val display = cursor.getString(1).orEmpty()
                if (ContactMatch.matches(name, display)) return@use number
            }
            null
        }
    }

    /** The device owner's own number, from the contacts Profile. Null if they never set one. */
    private fun ownNumber(): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.Profile.CONTENT_URI,
            ContactsContract.Contacts.Data.CONTENT_DIRECTORY,
        )
        return context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
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
