/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/** A contact that matched, carrying the number so a chosen option can be acted on. */
data class Contact(val name: String, val number: String)

/** The outcome of resolving a spoken name to a number. */
sealed interface ContactResolution {
    /** Exactly one contact — or a literal number, or the owner — matched. */
    data class One(val number: String) : ContactResolution

    /** Several distinct contacts matched; the caller must let the user choose, not guess. */
    data class Many(val options: List<Contact>) : ContactResolution

    /** Nothing matched, or there was no way to resolve. */
    data object None : ContactResolution
}

/**
 * Turns a spoken name into a phone number.
 *
 * If the name is already a number it is used as-is, so messaging works even without
 * contacts access. "me" resolves to the phone's own owner, never a contact. Otherwise it
 * queries the contacts provider — but the provider's filter is loose (it returned "MI
 * Aziz" for "me"), so a candidate is only accepted when its name genuinely matches. A
 * message sent to the wrong person is the worst outcome here; "not found" is far better.
 */
class ContactLookup(
    private val context: Context,
    /** The owner's number as they saved it in settings; null until they have. */
    private val ownNumber: () -> String? = { null },
) {

    /** The number to use, or an ambiguity/absence the caller must handle — never a guess. */
    fun resolve(spoken: String): ContactResolution {
        val trimmed = spoken.trim()
        return when {
            trimmed.isEmpty() -> ContactResolution.None

            looksLikeNumber(trimmed) -> ContactResolution.One(digitsOf(trimmed))

            // "me" is the number the owner typed in; the contacts Profile is only a fallback.
            ContactMatch.isSelf(trimmed) ->
                (savedOwnNumber() ?: profileNumberIfAllowed())?.let(ContactResolution::One)
                    ?: ContactResolution.None

            !hasContactsPermission() -> ContactResolution.None

            else -> lookupByName(trimmed).takeUnless { it == ContactResolution.None } ?: lookupByTag(trimmed)
        }
    }

    /**
     * Whether [spoken] is a name to ask about: contacts can be searched and nothing on them answers to it.
     * Never a number or "me", which are not names, and never without contacts access, where every name is unknown.
     */
    fun unknown(spoken: String): Boolean {
        val trimmed = spoken.trim()
        return trimmed.isNotEmpty() && hasContactsPermission() && !looksLikeNumber(trimmed) &&
            !ContactMatch.isSelf(trimmed) && resolve(trimmed) == ContactResolution.None
    }

    /** Contacts whose names sound like [spoken], to ask about and never to act on. */
    fun soundsLike(spoken: String): List<Contact> {
        if (!hasContactsPermission()) return emptyList()
        val found = LinkedHashMap<Long, Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(1) ?: continue
                val display = cursor.getString(2).orEmpty()
                if (!found.containsKey(cursor.getLong(0)) && ContactMatch.soundsAlike(spoken, display)) {
                    found[cursor.getLong(0)] = Contact(display, number)
                }
            }
        }
        return found.values.take(MAX_OPTIONS)
    }

    /**
     * Adds [heard] as a line of the notes of the contact with [number], so the next "text [heard]" finds
     * them (see [lookupByTag]). Only after the owner said who [heard] was. False if it could not be saved.
     */
    fun remember(number: String, heard: String): Boolean {
        val line = ContactMatch.tagLine(heard)
        if (line.isEmpty()) return false
        return try {
            val contactId = contactIdOf(number) ?: return false
            val note = noteOf(contactId)
            when {
                note == null -> addNote(contactId, line)
                ContactMatch.taggedAs(note.second, line) -> true
                else -> setNote(note.first, note.second.trimEnd().let { if (it.isEmpty()) line else "$it\n$line" })
            }
        } catch (e: SecurityException) {
            Log.w("Assistant", "no permission to change contacts", e)
            false
        }
    }

    private fun contactIdOf(number: String): Long? = context.contentResolver.query(
        Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
        arrayOf(ContactsContract.PhoneLookup._ID),
        null,
        null,
        null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    /** The contact's note row and its text, if it has one. */
    private fun noteOf(contactId: Long): Pair<Long, String>? = context.contentResolver.query(
        ContactsContract.Data.CONTENT_URI,
        arrayOf(ContactsContract.Data._ID, ContactsContract.CommonDataKinds.Note.NOTE),
        "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
        arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
        null,
    )?.use { if (it.moveToFirst()) it.getLong(0) to it.getString(1).orEmpty() else null }

    private fun setNote(rowId: Long, text: String): Boolean = context.contentResolver.update(
        ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, rowId),
        ContentValues().apply { put(ContactsContract.CommonDataKinds.Note.NOTE, text) },
        null,
        null,
    ) == 1

    /** A first note, on the contact's first account. */
    private fun addNote(contactId: Long, text: String): Boolean {
        val rawId = context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()),
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return false
        val values = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Note.NOTE, text)
        }
        return context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values) != null
    }

    /** The single unambiguous number, or null when absent OR ambiguous. Used by dry probes. */
    fun resolveNumber(spoken: String): String? = (resolve(spoken) as? ContactResolution.One)?.number

    private fun digitsOf(number: String): String = number.filter { it.isDigit() || it == '+' }

    private fun savedOwnNumber(): String? = ownNumber()?.let(::digitsOf)?.takeIf { it.length >= MIN_DIGITS }

    private fun profileNumberIfAllowed(): String? = if (hasContactsPermission()) profileNumber() else null

    /**
     * Every distinct contact whose display name genuinely matches the spoken name, deduped
     * by person. One resolves; several is an ambiguity to hand back for the user to choose
     * — "Hassan" matched both "Hassan" and "Dr Hassan ER Chicago", and a blind pick sent a
     * message to the wrong one. The query is driven by the most distinctive word (the
     * longest — a surname beats a common first name); the full-name rule filters the rows.
     */
    private fun lookupByName(name: String): ContactResolution {
        val words = name.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val byContact = LinkedHashMap<Long, Contact>()
        for (key in words.sortedByDescending { it.length }) {
            collectMatches(key, name, byContact)
        }
        // Prefer an exact whole-name match: "Hasan" should reach the contact named exactly
        // "Hasan", not offer every "Hasan Something" — a common first name matched fourteen
        // people. Only when no single exact match exists is it a real choice for the user.
        val exact = byContact.values.filter { it.name.trim().equals(name.trim(), ignoreCase = true) }
        val matches = exact.ifEmpty { byContact.values.toList() }
        return when (matches.size) {
            0 -> ContactResolution.None
            1 -> ContactResolution.One(matches.first().number)
            else -> ContactResolution.Many(matches.take(MAX_OPTIONS))
        }
    }

    /**
     * Contacts the owner tagged with what was said, in their notes: a note line "Assistant" makes
     * "text my assistant" that contact, a word Whisper hears where a name like "Hasan" was misheard.
     * Tried only when no name matched, and resolved like a name: one is used, several are a choice.
     */
    private fun lookupByTag(spoken: String): ContactResolution {
        val tagged = LinkedHashSet<Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Note.NOTE),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (ContactMatch.taggedAs(cursor.getString(1).orEmpty(), spoken)) tagged += cursor.getLong(0)
            }
        }
        val contacts = tagged.mapNotNull(::firstNumberOf)
        return when (contacts.size) {
            0 -> ContactResolution.None
            1 -> ContactResolution.One(contacts.first().number)
            else -> ContactResolution.Many(contacts.take(MAX_OPTIONS))
        }
    }

    private fun firstNumberOf(contactId: Long): Contact? = context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
        arrayOf(contactId.toString()),
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.let { Contact(cursor.getString(1).orEmpty(), it) } else null
    }

    private fun collectMatches(filterKey: String, name: String, into: MutableMap<Long, Contact>) {
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(filterKey),
        )
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(0)
                val number = cursor.getString(1) ?: continue
                val display = cursor.getString(2).orEmpty()
                // First number per person; a second number for someone already matched is
                // the same person, not a new ambiguity.
                if (ContactMatch.matches(name, display) && !into.containsKey(contactId)) {
                    into[contactId] = Contact(display, number)
                }
            }
        }
    }

    /** The device owner's number from the contacts Profile — usually unset, hence the setting. */
    private fun profileNumber(): String? {
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

        /** A selector longer than this is unusable; the user says a fuller name instead. */
        const val MAX_OPTIONS = 6
        val WHITESPACE = Regex("\\s+")
    }
}
