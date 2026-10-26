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
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The contact names to bias speech recognition toward.
 *
 * Whisper prefers real English words, so an unusual name is transcribed as the nearest one
 * — "Hasan" becomes "Hudson" — and the wrong contact, or none, is chosen. Seeding the
 * decoder with the actual names makes them candidates.
 *
 * Only the people the user actually calls and texts are worth it, plus the contacts they
 * starred: [ContactRanking] chooses them. The contacts provider's own "times contacted"
 * stopped meaning anything in Android 10 (every contact reads 0), so ranking by it handed
 * Whisper an arbitrary slice of the address book, and a list of strangers is worse than no
 * list at all.
 */
class ContactNames(private val context: Context) {

    /**
     * The names, joined for Whisper's prompt; empty when there is no access or no signal.
     * Best first: on real captures, the name at the start of the list was the one heard
     * right most often.
     *
     * Read before the microphone opens, so every query is bounded: a [WINDOW_DAYS]-day window,
     * at most [MAX_ROWS] rows from each log, and a capped number of name lookups. The call
     * log and SMS permissions are optional; either alone still ranks. Names and numbers are
     * never logged.
     */
    fun forBias(limit: Int = MAX_NAMES): String {
        if (!granted(Manifest.permission.READ_CONTACTS)) return ""

        val now = System.currentTimeMillis()
        val since = now - WINDOW_DAYS * MILLIS_PER_DAY
        val exchanges = buildList {
            if (granted(Manifest.permission.READ_CALL_LOG)) {
                val calls = CallLog.Calls.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, MAX_ROWS.toString())
                    .build()
                addAll(exchanges(calls, CallLog.Calls.NUMBER, CallLog.Calls.DATE, since))
            }
            if (granted(Manifest.permission.READ_SMS)) {
                addAll(exchanges(Telephony.Sms.CONTENT_URI, Telephony.Sms.ADDRESS, Telephony.Sms.DATE, since))
            }
        }
        val starred = starred()
        val names = ContactRanking.rank(exchanges, starred, now, limit, ::nameOf)
        Log.i(TAG, "bias: ${names.size} names from ${exchanges.size} calls and texts, ${starred.size} starred")
        return names.joinToString(", ")
    }

    /** The newest calls or texts since [since], newest first and at most [MAX_ROWS] of them. */
    private fun exchanges(uri: Uri, numberColumn: String, dateColumn: String, since: Long): List<Exchange> =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(numberColumn, dateColumn),
                "$dateColumn > ?",
                arrayOf(since.toString()),
                "$dateColumn DESC",
            )?.use { cursor ->
                buildList {
                    while (size < MAX_ROWS && cursor.moveToNext()) {
                        val number = cursor.getString(0) ?: continue
                        add(Exchange(number, cursor.getLong(1)))
                    }
                }
            }
        }.getOrNull().orEmpty()

    private fun starred(): List<String> = runCatching {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, MAX_ROWS.toString())
                .build(),
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts.STARRED} = 1",
            null,
            null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) cursor.getString(0)?.let(::add) }
        }
    }.getOrNull().orEmpty()

    /** The contact a number belongs to, matched the way the dialler matches it. */
    private fun nameOf(number: String): String? = runCatching {
        context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "TheMachine"

        /**
         * Two, measured. On real captures replayed through base.en, the first name in a list
         * of two was heard right as reliably as a name on its own, and the second name cost
         * nothing measurable. From three names up, a name missing from the list, commands
         * without a name and plain silence all came out worse; from eight up, so did the
         * names on it.
         */
        const val MAX_NAMES = 2

        /** How far back a call or text still says something about who the user talks to. */
        const val WINDOW_DAYS = 90L

        /** Rows read from each log, newest first. */
        const val MAX_ROWS = 500

        const val MILLIS_PER_DAY = 86_400_000L
    }
}
