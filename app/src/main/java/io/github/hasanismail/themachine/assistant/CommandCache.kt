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
import io.github.hasanismail.themachine.tools.ToolParam
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Commands the model has already resolved once, so the same shape of phrase never waits
 * for it again.
 *
 * "Timer ten minutes" has exactly one meaning, and once that is known the same is true of
 * "timer thirty minutes" — so what is remembered is the phrase with its numbers taken
 * out, and the numbers are read back from whatever was said this time. One entry covers
 * every length of timer rather than one entry per length.
 *
 * A hit skips the language model entirely: the command runs the moment transcription
 * ends, and the model does not even have to have finished loading.
 *
 * What may be remembered is decided by three rules and nothing cleverer. The tool must be
 * one whose call is a pure function of the words — see [CACHEABLE]. A phrase whose meaning
 * moves with the clock is never learned for a tool that reads the clock. And every
 * argument must be traceable to something actually said, so a misreading cannot be frozen
 * in place. A phrase failing any of them takes the ordinary path every time, which costs a
 * second and is correct.
 */
class CommandCache internal constructor(private val file: File) {

    /**
     * Where a number in the call came from in the phrase.
     *
     * [index] is which spoken number, counting from the left. [offset] is what was added
     * to it, which is only ever twelve: "alarm at 6pm" resolves to hour 18, and the same
     * shape said with a nine has to resolve to 21 rather than to 18 again.
     */
    @Serializable
    data class Slot(val argument: String, val index: Int, val offset: Int = 0)

    @Serializable
    data class Entry(
        /** The phrase with its numbers replaced by [NUMBER], so one entry covers many. */
        val key: String,
        val tool: String,
        /** Arguments that do not vary: a task's text, a scroll direction. */
        val arguments: Map<String, String> = emptyMap(),
        /** Arguments read back out of the phrase each time. */
        val slots: List<Slot> = emptyList(),
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

    /** The call a phrase has been seen to mean, or null if its shape has not been seen. */
    fun lookup(transcript: String, now: Long = System.currentTimeMillis()): ToolCall? {
        val shape = Shape.of(transcript) ?: return null
        return synchronized(this) {
            load()
            val entry = entries[shape.key] ?: return@synchronized null
            val filled = fill(entry, shape) ?: return@synchronized null
            entries[shape.key] = entry.copy(hits = entry.hits + 1, lastUsedEpochMillis = now)
            persist()
            filled
        }
    }

    /**
     * Records what a phrase meant, if it is the kind of phrase whose meaning is fixed.
     *
     * Returns true if it was remembered. Learning happens on the first success, because
     * the point is that the second utterance be instant — and because a wrong resolution
     * would already have been carried out once and noticed.
     */
    fun learn(transcript: String, call: ToolCall, now: Long = System.currentTimeMillis()): Boolean {
        val shape = Shape.of(transcript)?.takeIf { it.key.split(' ').size >= MIN_WORDS } ?: return false
        val clockBound = call.tool in TIME_BEARING && CommandKey.isTimeRelative(transcript)
        if (call.tool !in CACHEABLE || clockBound) return false

        val entry = describe(shape, call, now) ?: return false
        synchronized(this) {
            load()
            entries[shape.key] = entry
            evict()
            persist()
        }
        Log.i(TAG, "cache: learned [${shape.key}] -> ${call.tool} ${call.arguments} slots=${entry.slots}")
        return true
    }

    /**
     * Turns one resolved call into an entry, or refuses.
     *
     * Every argument has to be accounted for: a number becomes a slot pointing at the
     * spoken number it came from, and free text has to be a run of words from the phrase
     * itself. Anything the model produced that the words do not explain — a minute it
     * invented, a task rewritten through the user's notes, an app name it un-mangled —
     * means the phrase is not understood well enough to answer without it.
     */
    private fun describe(shape: Shape, call: ToolCall, now: Long): Entry? {
        val slots = ArrayList<Slot>()
        val fixed = LinkedHashMap<String, String>()

        for ((name, value) in call.arguments) {
            when (val origin = originOf(shape, call, name, value)) {
                null -> return null
                is Origin.Spoken -> slots += origin.slot
                is Origin.Fixed -> fixed[name] = value
            }
        }
        return Entry(shape.key, call.tool, fixed, slots, hits = 0, lastUsedEpochMillis = now)
    }

    /** Where one argument came from, or null if the words do not explain it. */
    private sealed interface Origin {
        data class Spoken(val slot: Slot) : Origin
        data object Fixed : Origin
    }

    private fun originOf(shape: Shape, call: ToolCall, name: String, value: String): Origin? = when {
        // A flag is explained by its own name being in the phrase: "tomorrow" set on a
        // command that never said tomorrow is the model inventing a day.
        value == "true" || value == "false" ->
            Origin.Fixed.takeIf { (value == "true") == shape.said(name) }

        value.toIntOrNull() == null ->
            Origin.Fixed.takeIf { shape.saidAsText(value) }

        shape.slotFor(name, value.toInt()) != null ->
            Origin.Spoken(shape.slotFor(name, value.toInt())!!)

        // A default the model filled in, believable only when nothing spoken is left
        // unexplained: "6:30" answered as minute 0 is a misreading, not a default, and
        // freezing it would set the wrong alarm every morning after.
        value == "0" && shape.everyNumberClaimed(call.arguments) -> Origin.Fixed

        else -> null
    }

    /** Rebuilds a call from an entry and the numbers in this particular phrase. */
    private fun fill(entry: Entry, shape: Shape): ToolCall? {
        val arguments = LinkedHashMap(entry.arguments)
        for (slot in entry.slots) {
            val spoken = shape.numbers.getOrNull(slot.index) ?: return null
            val value = spoken + slot.offset
            // Checked against the same bounds the grammar would have enforced, so a shape
            // learned from "timer 10 minutes" cannot produce an hour of forty.
            if (!withinRange(entry.tool, slot.argument, value)) return null
            arguments[slot.argument] = value.toString()
        }
        return ToolCall(entry.tool, arguments)
    }

    private fun withinRange(tool: String, argument: String, value: Int): Boolean {
        val parameter: ToolParam? = MachineTools.all.firstOrNull { it.name == tool }
            ?.params?.firstOrNull { it.name == argument }
        val range = parameter?.range ?: return value >= 0
        return value in range
    }

    /** Drops one phrase, for when the assistant got it wrong. */
    fun forget(transcript: String) {
        val shape = Shape.of(transcript) ?: return
        forgetKey(shape.key)
    }

    /** Drops one remembered shape by its key, as the History screen lists it. */
    fun forgetKey(key: String) {
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
            // Filtered on the way in as well as on the way out: the file is ordinary JSON
            // in shared storage, and an entry naming send_message would otherwise be run
            // instantly and silently on a phrase that never asked for it.
            .onSuccess { stored ->
                stored.entries.filter { it.tool in CACHEABLE }.forEach { entries[it.key] = it }
            }
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

    /**
     * One phrase reduced to the shape it shares with every other phrasing of the same
     * command, plus the numbers lifted out of it.
     */
    class Shape(val key: String, val numbers: List<Int>, private val words: Set<String>) {

        /** The slot a numeric argument came from, or null if the words do not explain it. */
        fun slotFor(argument: String, value: Int): Slot? {
            val direct = numbers.indexOfFirst { it == value }
            if (direct >= 0) return Slot(argument, direct)
            // The one piece of arithmetic the model is allowed to have done already.
            val afternoon = numbers.indexOfFirst { it + NOON == value }
            return if (afternoon >= 0) Slot(argument, afternoon, NOON) else null
        }

        /** Whether a particular word was said. */
        fun said(word: String): Boolean = word in words

        /** Whether every word of a free-text argument appeared in the phrase. */
        fun saidAsText(value: String): Boolean {
            val normalised = CommandKey.of(value) ?: return false
            return normalised.split(' ').all { it in words }
        }

        /** Whether the call accounts for every number that was said. */
        fun everyNumberClaimed(arguments: Map<String, String>): Boolean {
            val claimed = arguments.values.mapNotNull { it.toIntOrNull() }
            return numbers.all { spoken -> claimed.any { it == spoken || it == spoken + NOON } }
        }

        companion object {
            fun of(transcript: String): Shape? {
                val normalised = CommandKey.of(transcript) ?: return null
                val numbers = ArrayList<Int>()
                val key = DIGITS.replace(normalised) { match ->
                    numbers += match.value.toInt()
                    NUMBER
                }
                return Shape(key, numbers, normalised.split(' ').toSet())
            }

            private val DIGITS = Regex("""\d+""")
        }
    }

    companion object {
        private const val TAG = "TheMachine"
        const val MAX_ENTRIES = 300
        const val FILE_NAME = "command-cache.json"

        /** Stands in for any number, so one entry covers every length of timer. */
        const val NUMBER = "#"

        /** A single word is not a command anyone would want run without being asked. */
        const val MIN_WORDS = 2

        private const val NOON = 12

        private val instances = HashMap<String, CommandCache>()

        /**
         * The one cache for a file, for the life of the process.
         *
         * The map is the cache and the file is only its backing store, so a second object
         * over the same path keeps serving entries the first one erased and writes them
         * back on its next save. "Forget learned" appeared to do nothing: the screen
         * cleared its own copy while the assistant went on answering from the copy it had
         * already loaded.
         */
        fun shared(file: File): CommandCache = synchronized(instances) {
            instances.getOrPut(file.absolutePath) { CommandCache(file) }
        }

        /**
         * Tools whose arguments are read off the clock.
         *
         * Only these care whether the phrase was relative. Applying the veto to every tool
         * meant "read this" was never learned, because "this" is in the list — a phrase
         * with no time in it at all, permanently barred from the fast path.
         */
        val TIME_BEARING: Set<String> = setOf(
            MachineTools.SET_ALARM,
            MachineTools.SET_TIMER,
            MachineTools.CREATE_REMINDER,
        )

        /**
         * Tools whose call is decided by the words alone.
         *
         * Absent on purpose: `answer`, which reads the user's notes and the date;
         * `unsupported`, which should be re-tried once a tool exists for it;
         * `send_message` and `call_contact`, where an instant wrong action reaches another
         * person and the phrases are rarely repeated verbatim anyway; and `tap_text`,
         * whose label has to match what is on screen exactly, which the normalised key
         * cannot promise.
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
