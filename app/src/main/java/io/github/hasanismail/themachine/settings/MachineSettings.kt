/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "machine_settings")

/**
 * Small persistent settings. DataStore rather than SharedPreferences per CLAUDE.md,
 * and deliberately narrow: anything the model needs to *read about the user* lives in
 * the Markdown context files instead, where the user can see and edit it.
 */
class MachineSettings(private val context: Context) {

    val adminName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[ADMIN_NAME]?.takeIf { it.isNotBlank() } ?: DEFAULT_ADMIN_NAME
    }

    suspend fun adminNameNow(): String = adminName.first()

    suspend fun setAdminName(name: String) {
        context.dataStore.edit { prefs ->
            val trimmed = name.trim()
            if (trimmed.isEmpty()) prefs.remove(ADMIN_NAME) else prefs[ADMIN_NAME] = trimmed
        }
    }

    /**
     * The owner's own phone number, so "text me" has somewhere to go. Null until they
     * enter it: the contacts Profile is usually empty, and guessing (a SIM's line number,
     * a fuzzy contact) is how a message once went to a relative instead.
     */
    val ownNumber: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[OWN_NUMBER]?.takeIf { it.isNotBlank() }
    }

    suspend fun ownNumberNow(): String? = ownNumber.first()

    suspend fun setOwnNumber(number: String) {
        context.dataStore.edit { prefs ->
            val trimmed = number.trim()
            if (trimmed.isEmpty()) prefs.remove(OWN_NUMBER) else prefs[OWN_NUMBER] = trimmed
        }
    }

    companion object {
        /**
         * The Machine calls its operator "Admin" until told otherwise — which is both
         * the sensible default and the right register for the thing.
         */
        const val DEFAULT_ADMIN_NAME = "Admin"

        private val ADMIN_NAME = stringPreferencesKey("admin_name")
        private val OWN_NUMBER = stringPreferencesKey("own_number")
    }
}
