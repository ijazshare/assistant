/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.assistant

import android.util.Log
import io.github.hasanismail.themachine.tools.MachineTools
import io.github.hasanismail.themachine.tools.ToolCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Commands the model has already resolved once, so the same phrase never waits for it
 * again.
 *
 * "Timer ten minutes" has exactly one meaning, and the second time it is said the answer
 * is already known. A hit here skips the language model entirely: the command runs the
 * moment transcription ends, and the model does not even have to have finished loading.
 *
 * What may be cached is decided by two rules and nothing cleverer. The tool must be one
 * whose call is a pure function of the words — see [CACHEABLE] — and the words must not
 * depend on when they were said — see [CommandKey.isTimeRelative]. A phrase that fails
 * either rule takes the ordinary path every time, which costs a second and is correct.
 */
class CommandCache(private val file: File) {

    @Serializable
    data class Entry(
        val key: String,
        val tool: String,
        val arguments: Map<String, String>,
        val hits: Int = 0,
        val lastUsedEpochMillis: Long,
    )

    @Serializable
    private data class Stored(val entries: List<Entry> = emptyList())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val entries = LinkedHashMap<String, Entry>()

    @Volatile
    private var loaded = false

    val size: Int
        get() = synchronized(this) {
            load()
            entries.size
        }

    /** The call a phrase has been seen to mean, or null if it has not been seen. */
    fun lookup(transcript: String, now: Long = System.currentTimeMillis()): ToolCall? {
        val key = CommandKey.of(transcript) ?: return null
        return synchronized(this) {
            load()
            val entry = entries[key] ?: return@synchronized null
            entries[key] = entry.copy(hits = entry.hits + 1, lastUsedEpochMillis = now)
            persist()
            ToolCall(entry.tool, entry.arguments)
        }
    }

    /**
     * Records what a phrase meant, if it is the kind of phrase whose meaning is fixed.
     *
     * Returns true if it was cached. Caching happens on the first success, because the
     * owner's requirement is that the second utterance be instant — and because a wrong
     * resolution would already have been carried out once and noticed.
     */
    fun learn(transcript: String, call: ToolCall, now: Long = System.currentTimeMillis()): Boolean {
        val key = CommandKey.of(transcript)?.takeIf { it.split(' ').size >= MIN_WORDS }
        if (key == null || call.tool !in CACHEABLE || CommandKey.isTimeRelative(transcript)) return false
        if (!saidInWords(key, call)) return false

        synchronized(this) {
            load()
            entries[key] = Entry(key, call.tool, call.arguments, hits = 0, lastUsedEpochMillis = now)
            evict()
            persist()
        }
        Log.i(TAG, "cache: learned [$key] -> ${call.tool} ${call.arguments}")
        return true
    }

    /**
     * Whether every argument the model produced can be traced to the words themselves.
     *
     * This is what stops a misreading from being frozen. "Half past six" resolved to
     * minute 30 is right, but 30 appears nowhere in the phrase, so it cannot be told apart
     * from a guess and is not cached; "6:30" can be, and is. A reminder whose task text
     * was rewritten through the user's notes — "call my brother" becoming "call Osman" —
     * depends on those notes and is left to the model each time. An app name the model
     * un-mangled from a misheard phrase is left alone for the same reason.
     */
    private fun saidInWords(key: String, call: ToolCall): Boolean {
        val said = Said(key.split(' ').toSet(), CommandKey.numbersIn(key))
        val args = call.arguments
        return when (call.tool) {
            MachineTools.SET_ALARM -> said.hour(args["hour"]) && said.number(args["minute"])
            MachineTools.SET_TIMER -> DURATION_PARTS.all { said.number(args[it]) }
            MachineTools.CREATE_REMINDER -> said.reminder(args)
            MachineTools.OPEN_APP -> said.text(args["app"])
            else -> true
        }
    }

    /** The words and numbers of a key, and what they vouch for. */
    private class Said(private val words: Set<String>, private val numbers: Set<String>) {

        /** An hour is vouched for by itself, its 12-hour form, or noon and midnight by name. */
        fun hour(value: String?): Boolean {
            val hour = value?.toIntOrNull() ?: return value == null
            return hour.toString() in numbers ||
                (hour > NOON && (hour - NOON).toString() in numbers) ||
                (hour == 0 && (NOON.toString() in numbers || "midnight" in words)) ||
                (hour == NOON && "noon" in words)
        }

        /** Zero is the model filling a default in, not a number it heard. */
        fun number(value: String?): Boolean = value == null || value == "0" || value in numbers

        /** Every word of a free-text argument must be a word of the command. */
        fun text(value: String?): Boolean {
            val normalised = CommandKey.of(value ?: return true) ?: return false
            return normalised.split(' ').all { it in words }
        }

        fun reminder(args: Map<String, String>): Boolean =
            hour(args["hour"]) &&
                number(args["minute"]) &&
                text(args["task"]) &&
                (args["tomorrow"] == "true") == ("tomorrow" in words)
    }

    /** Drops one phrase, for when the user says the assistant got it wrong. */
    fun forget(transcript: String) {
        val key = CommandKey.of(transcript) ?: return
        synchronized(this) {
            load()
            if (entries.remove(key) != null) persist()
        }
    }

    fun clear() {
        synchronized(this) {
            entries.clear()
            loaded = true
            file.delete()
        }
    }

    fun all(): List<Entry> = synchronized(this) {
        load()
        entries.values.sortedByDescending { it.lastUsedEpochMillis }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        runCatching { json.decodeFromString<Stored>(file.readText()) }
            .onSuccess { stored -> stored.entries.forEach { entries[it.key] = it } }
            .onFailure { Log.w(TAG, "cache: unreadable, starting empty", it) }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(Stored(entries.values.toList())))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "cache: could not persist", it) }
    }

    /** Least recently used first, once the bound is exceeded. */
    private fun evict() {
        if (entries.size <= MAX_ENTRIES) return
        entries.values.sortedBy { it.lastUsedEpochMillis }
            .take(entries.size - MAX_ENTRIES)
            .forEach { entries.remove(it.key) }
    }

    companion object {
        private const val TAG = "TheMachine"
        const val MAX_ENTRIES = 300
        const val FILE_NAME = "command-cache.json"

        /** A single word is not a command anyone would want run without being asked. */
        const val MIN_WORDS = 2

        private const val NOON = 12
        private val DURATION_PARTS = listOf("hours", "minutes", "seconds")

        /**
         * Tools whose call is decided by the words alone.
         *
         * Absent on purpose: `answer`, which reads the user's notes and the date;
         * `unsupported`, which should be re-tried once a tool exists for it;
         * `send_message` and `call_contact`, where an instant wrong action reaches
         * another person and the phrases are rarely repeated verbatim anyway; and
         * `tap_text`, whose label has to match what is on screen exactly, which the
         * normalised key cannot promise.
         */
        val CACHEABLE: Set<String> = setOf(
            MachineTools.SET_ALARM,
            MachineTools.SET_TIMER,
            MachineTools.SHOW_ALARMS,
            MachineTools.CREATE_REMINDER,
            MachineTools.OPEN_APP,
            MachineTools.READ_SCREEN,
            MachineTools.READ_NOTIFICATIONS,
            MachineTools.SCROLL,
            MachineTools.NAVIGATE,
        )
    }
}
