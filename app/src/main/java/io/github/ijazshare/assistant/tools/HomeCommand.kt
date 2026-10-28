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

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A command for the owner's Home Assistant, read from the words by code and never by the model:
 * "Homie, turn off the kitchen light", or a question, "Homie, is the garage door open?"
 *
 * Only a sentence that starts with the trigger word is one, and its grammar is fixed. A switch is turn
 * or switch, on or off, and a name, in either order. A question is "is (or are) the name" and one of
 * on, off, open, closed, locked or unlocked, or "what is the name". Any other words are the name of a
 * script, "Homie, pink fan medium", so the owner makes the phrases. Nothing is guessed at. The name goes
 * to Home Assistant as said, in lower case with punctuation dropped, and the owner's automation there
 * acts on, reads or runs only the one labelled device or script whose name, reduced the same way, is
 * exactly that.
 */
sealed interface HomeCommand {
    val name: String

    /** Switches a light, switch or fan on or off. */
    data class Switch(override val name: String, val on: Boolean) : HomeCommand

    /** Asks for the state, and with [asked] ("open"), whether it is that. */
    data class Ask(override val name: String, val asked: String? = null) : HomeCommand

    /** Runs the script the owner gave this name. */
    data class Run(override val name: String) : HomeCommand

    /** What the automation is told to do. */
    val action: String
        get() = when (this) {
            is Switch -> if (on) TURN_ON else TURN_OFF
            is Ask -> STATE
            is Run -> RUN
        }

    /** The share's text. [id] is what ties Home Assistant's reply to this command. */
    fun json(id: String): String = buildJsonObject {
        put("do", action)
        put("name", name)
        put("id", id)
    }.toString()

    fun toCall() = ToolCall(
        AssistantTools.HOME,
        buildMap {
            put("do", action)
            put("name", name)
            (this@HomeCommand as? Ask)?.asked?.let { put("asked", it) }
        },
    )

    /**
     * What to say for Home Assistant's [reply] (its `status`, and for a question the `state`), or for
     * no reply at all. Only what the reply proves: "on" only when Home Assistant saw the new state.
     */
    fun said(reply: Map<String, String>?): ToolResult {
        val status = reply?.get("status")
        return when {
            reply == null -> ToolResult.failed(
                if (this is Ask) NO_ANSWER else "Home Assistant did not answer, so I do not know if that worked.",
            )

            // Two allowed devices share the name, so which one was meant would be a guess.
            status == "ambiguous" -> ToolResult.failed(
                "Home Assistant has more than one $name. Rename one so I know which.",
            )

            else -> when (this) {
                is Switch -> switched(status)

                is Ask -> if (status == "done") {
                    answer(reply)
                } else {
                    ToolResult.failed("Home Assistant has nothing called $name that I may check.")
                }

                // Started, not finished: Home Assistant runs the script on its own.
                is Run -> if (status == "done") {
                    ToolResult.ok("Started $name.")
                } else {
                    ToolResult.failed("Home Assistant has no script called $name that I may run.")
                }
            }
        }
    }

    private fun Switch.switched(status: String?): ToolResult {
        val state = if (on) "on" else "off"
        return when (status) {
            "done" -> ToolResult.ok("${spokenName()} is $state.")
            "unsure" -> ToolResult.failed("I asked, but Home Assistant cannot confirm $name is $state.")
            else -> ToolResult.failed("Home Assistant has no light, switch or fan called $name that I may switch.")
        }
    }

    private fun spokenName() = name.replaceFirstChar(Char::uppercase)

    private fun Ask.answer(reply: Map<String, String>): ToolResult {
        val state = reply["state"].orEmpty().lowercase()
        if (state.isEmpty() || state in NOT_KNOWN) {
            return ToolResult.failed(
                if (asked != null) "Home Assistant does not know if $name is $asked right now." else NO_STATE,
            )
        }
        val value = listOf(
            state,
            reply["unit"].orEmpty().let {
                SPOKEN_UNITS[it] ?: it
            },
        ).filter(String::isNotEmpty).joinToString(" ")
        return ToolResult.ok(
            when {
                state == asked -> "Yes, $name is $asked."

                // Only the opposite is a no. "Is the front door unlocked?" answered "No" from a door
                // sensor's "closed" would be a guess about a lock nobody read.
                asked != null && OPPOSITE[asked] == state -> "No, $name is $value."

                asked != null -> "${spokenName()} is $value."

                else -> "${spokenName()}: $value.${climate(reply)}"
            },
        )
    }

    companion object {
        /** Whether [transcript] starts with the trigger word, which makes it a home command, understood or not. */
        fun addressed(transcript: String, trigger: String): Boolean {
            val said = words(trigger)
            return said.isNotEmpty() && words(transcript).take(said.size) == said
        }

        /** The command in [transcript], or null when the words after the trigger are not one. */
        fun parse(transcript: String, trigger: String): HomeCommand? {
            if (!addressed(transcript, trigger)) return null
            val rest = words(transcript).drop(words(trigger).size)
                .dropWhile { it == PLEASE }.dropLastWhile { it == PLEASE }
            return switched(rest) ?: asked(rest) ?: ran(rest)
        }

        /** The command a [toCall] call carries, or null for one the words did not make. */
        fun of(call: ToolCall): HomeCommand? {
            val name = call.string("name") ?: return null
            return when (call.string("do")) {
                TURN_ON -> Switch(name, on = true)
                TURN_OFF -> Switch(name, on = false)
                STATE -> Ask(name, call.string("asked"))
                RUN -> Run(name)
                else -> null
            }
        }

        /** "turn off the light" or "turn the light off". Both at once ("turn on the light off") is neither. */
        private fun switched(rest: List<String>): HomeCommand? {
            val stateFirst = rest.getOrNull(1) in SWITCHED
            val stateLast = rest.size > 2 && rest.last() in SWITCHED
            if (rest.firstOrNull() !in VERBS || stateFirst == stateLast) return null
            val name = named(if (stateFirst) rest.drop(2) else rest.drop(1).dropLast(1)) ?: return null
            return Switch(name, on = (if (stateFirst) rest[1] else rest.last()) == "on")
        }

        /** "is the garage door open", "are the porch lights on", "what is the thermostat (set to)". */
        private fun asked(rest: List<String>): HomeCommand? = when {
            rest.firstOrNull() in IS && rest.size > 2 && rest.last() in CONDITIONS ->
                named(rest.drop(1).dropLast(1))?.let { Ask(it, rest.last()) }

            rest.take(2) in WHAT_IS -> named(withoutEnding(rest.drop(2)))?.let { Ask(it) }

            else -> null
        }

        /**
         * Any other words, whole: "status of" is dropped only from a question, so it never runs a script
         * named after what was asked about. A misheard switch or question is not a script's name either.
         */
        private fun ran(rest: List<String>): HomeCommand? =
            rest.dropWhile { it in ARTICLES }
                .takeIf { it.isNotEmpty() && rest.first() !in VERBS + IS && rest.take(2) !in WHAT_IS }
                ?.let { Run(it.joinToString(" ")) }

        private fun withoutEnding(words: List<String>) = when {
            words.takeLast(2) == listOf("set", "to") -> words.dropLast(2)
            words.lastOrNull() == "at" -> words.dropLast(1)
            else -> words
        }

        private fun named(words: List<String>): String? {
            val name = words.dropWhile { it in ARTICLES }
                .let { if (it.firstOrNull() in STATUS && it.getOrNull(1) == "of") it.drop(2) else it }
                .dropWhile { it in ARTICLES }
            return name.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

        // Home Assistant's automation reduces names with the same pattern, so both sides agree on "Mum's lamp".
        private fun words(text: String) = text.lowercase().split(NOT_WORD).filter(String::isNotEmpty)

        private fun climate(reply: Map<String, String>): String {
            val now = reply["temperature"].orEmpty()
            val target = reply["target"].orEmpty()
            return when {
                now.isNotEmpty() && target.isNotEmpty() -> " It is $now degrees, set to $target."
                now.isNotEmpty() -> " It is $now degrees."
                target.isNotEmpty() -> " It is set to $target."
                else -> ""
            }
        }

        private val SPOKEN_UNITS = mapOf("°C" to "degrees Celsius", "°F" to "degrees Fahrenheit", "%" to "percent")
        private val NOT_WORD = Regex("[^a-z0-9]+")
        private val VERBS = setOf("turn", "switch")
        private val SWITCHED = setOf("on", "off")
        private val IS = setOf("is", "are")
        private val OPPOSITE = mapOf(
            "on" to "off",
            "off" to "on",
            "open" to "closed",
            "closed" to "open",
            "locked" to "unlocked",
            "unlocked" to "locked",
        )
        private val CONDITIONS = OPPOSITE.keys

        // Whisper writes "what's" as two words once the apostrophe goes.
        private val WHAT_IS = setOf(listOf("what", "is"), listOf("what", "s"))
        private val STATUS = setOf("state", "status")
        private val ARTICLES = setOf("the", "my")
        private val NOT_KNOWN = setOf("unknown", "unavailable")
        private const val PLEASE = "please"
        private const val TURN_ON = "turn_on"
        private const val TURN_OFF = "turn_off"
        private const val STATE = "state"
        private const val RUN = "run"
        private const val NO_ANSWER = "Home Assistant did not answer."
        private const val NO_STATE = "Home Assistant does not know its state right now."
    }
}
