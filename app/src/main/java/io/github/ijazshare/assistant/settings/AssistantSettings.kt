/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "assistant_settings")

/**
 * Small persistent settings. DataStore rather than SharedPreferences per CLAUDE.md,
 * and deliberately narrow: anything the model needs to *read about the user* lives in
 * the Markdown context files instead, where the user can see and edit it.
 */
class AssistantSettings(private val context: Context) {

    val adminName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[ADMIN_NAME]?.takeIf { it.isNotBlank() } ?: DEFAULT_ADMIN_NAME
    }

    suspend fun adminNameNow(): String = adminName.first()

    suspend fun setAdminName(name: String) = setOrClear(ADMIN_NAME, name)

    /**
     * The owner's own phone number, so "text me" has somewhere to go. Null until they
     * enter it: the contacts Profile is usually empty, and guessing (a SIM's line number,
     * a fuzzy contact) is how a message once went to a relative instead.
     */
    val ownNumber: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[OWN_NUMBER]?.takeIf { it.isNotBlank() }
    }

    suspend fun ownNumberNow(): String? = ownNumber.first()

    suspend fun setOwnNumber(number: String) = setOrClear(OWN_NUMBER, number)

    /**
     * An optional remote language model for general-knowledge answers, spoken to over the
     * OpenAI chat-completions API so the same client works against OpenRouter today and a
     * llama.cpp server at home tomorrow. Only the transcript TEXT is ever sent; speech
     * recognition, tool routing and speech synthesis stay on the phone. Disabled — and
     * nothing leaves the device — until all three fields are filled in.
     */
    suspend fun remoteLlmNow(): RemoteLlmConfig? = context.dataStore.data.first().let { prefs ->
        val url = prefs[REMOTE_LLM_URL]?.trim().orEmpty()
        val key = prefs[REMOTE_LLM_KEY]?.trim().orEmpty()
        val model = prefs[REMOTE_LLM_MODEL]?.trim().orEmpty()
        if (url.isEmpty() || key.isEmpty() || model.isEmpty()) null else RemoteLlmConfig(url, key, model)
    }

    suspend fun remoteLlmFieldsNow(): Triple<String, String, String> {
        val prefs = context.dataStore.data.first()
        return Triple(
            prefs[REMOTE_LLM_URL].orEmpty(),
            prefs[REMOTE_LLM_KEY].orEmpty(),
            prefs[REMOTE_LLM_MODEL].orEmpty(),
        )
    }

    suspend fun setRemoteLlmUrl(value: String) = setOrClear(REMOTE_LLM_URL, value)
    suspend fun setRemoteLlmKey(value: String) = setOrClear(REMOTE_LLM_KEY, value)
    suspend fun setRemoteLlmModel(value: String) = setOrClear(REMOTE_LLM_MODEL, value)

    /**
     * The Home Assistant link, off until a key is set. A sentence that starts with the trigger word is
     * then a home command, handed with this key to the Home Assistant app on the phone; the owner's
     * automation acts only on shares that carry the same key.
     */
    suspend fun homeKeyNow(): String? = context.dataStore.data.first()[HOME_KEY]?.takeIf { it.isNotBlank() }

    // A trigger with no letter or digit the grammar can read would switch the link off unseen.
    suspend fun homeTriggerNow(): String =
        context.dataStore.data.first()[HOME_TRIGGER]?.takeIf {
            it.lowercase().any { c -> c in 'a'..'z' || c in '0'..'9' }
        }
            ?: DEFAULT_HOME_TRIGGER

    suspend fun homeFieldsNow(): Pair<String, String> {
        val prefs = context.dataStore.data.first()
        return prefs[HOME_KEY].orEmpty() to prefs[HOME_TRIGGER].orEmpty()
    }

    suspend fun setHomeKey(value: String) = setOrClear(HOME_KEY, value)
    suspend fun setHomeTrigger(value: String) = setOrClear(HOME_TRIGGER, value)

    /** Set once tasks.md has moved into Tasks (the migration, spec 4.13). */
    suspend fun tasksMigratedNow(): Boolean = context.dataStore.data.first()[TASKS_MIGRATED] == true

    suspend fun setTasksMigrated() {
        context.dataStore.edit { it[TASKS_MIGRATED] = true }
    }

    private suspend fun setOrClear(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { prefs ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) prefs.remove(key) else prefs[key] = trimmed
        }
    }

    companion object {
        /**
         * The assistant calls its operator "Admin" until told otherwise — which is both
         * the sensible default and the right register for the thing.
         */
        const val DEFAULT_ADMIN_NAME = "Admin"

        /** OpenRouter's OpenAI-compatible root; a home llama.cpp server is e.g. https://box.tailnet.ts.net/v1. */
        const val DEFAULT_REMOTE_LLM_URL = "https://openrouter.ai/api/v1"

        /** A word nobody says in passing, so a home command is never started by accident. */
        const val DEFAULT_HOME_TRIGGER = "homie"

        private val ADMIN_NAME = stringPreferencesKey("admin_name")
        private val HOME_KEY = stringPreferencesKey("home_key")
        private val HOME_TRIGGER = stringPreferencesKey("home_trigger")
        private val TASKS_MIGRATED = booleanPreferencesKey("tasks_migrated")
        private val OWN_NUMBER = stringPreferencesKey("own_number")
        private val REMOTE_LLM_URL = stringPreferencesKey("remote_llm_url")
        private val REMOTE_LLM_KEY = stringPreferencesKey("remote_llm_key")
        private val REMOTE_LLM_MODEL = stringPreferencesKey("remote_llm_model")
    }
}

/** Where to reach a remote model and how: an OpenAI-compatible base URL, bearer key and model id. */
data class RemoteLlmConfig(val baseUrl: String, val apiKey: String, val model: String)
