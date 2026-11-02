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

import io.github.ijazshare.assistant.tools.HomeCommand.Act
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.CLOSE
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.HVAC
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.LOCK
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.RUN
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.SPEED
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.STATE
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.TEMPERATURE
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.TURN_OFF
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.TURN_ON
import io.github.ijazshare.assistant.tools.HomeCommand.Decline
import io.github.ijazshare.assistant.tools.HomeDevices.Companion.ARTICLES
import io.github.ijazshare.assistant.tools.HomeDevices.Companion.words

/**
 * Reads a home command from the words, by code and never by the model: "Homie, turn off the pink
 * bedroom light", "Homie, cool to 72", "Homie, is the garage door open?"
 *
 * Only a sentence that starts with the trigger word is one. Its shape is fixed, every word must be
 * accounted for, and the words must name exactly one thing on the owner's list (or every light in
 * one room). Otherwise nothing is sent and Assistant says why: "turn on the pink bedroom light in
 * ten minutes" is declined, not run now. Unlocking and opening are never commands.
 */
object HomeGrammar {

    /** Whether [transcript] starts with the trigger word, which makes it a home command, understood or not. */
    fun addressed(transcript: String, trigger: String): Boolean {
        val said = greeted(trigger)
        return said.isNotEmpty() && greeted(transcript).take(said.size) == said
    }

    /** The words after any opening "hey" or "okay": "Hey homie, ..." is addressed to homie. */
    private fun greeted(text: String) = words(text).dropWhile { it in GREETINGS }

    /** What to hand to Home Assistant for [transcript], or why not. */
    fun read(transcript: String, trigger: String, home: HomeDevices): HomeCommand {
        val rest = greeted(transcript).drop(greeted(trigger).size)
            .dropWhile { it == PLEASE }.dropLastWhile { it == PLEASE }
        return when {
            home.all.isEmpty() -> Decline("Paste your Home Assistant device list into Context first.")

            rest.firstOrNull() in NEVER -> Decline("I don't unlock or open anything by voice.")

            // The owner's own phrases first, so a script called "Movie on" is never read as a switch.
            else -> asked(rest, home) ?: ran(rest, home) ?: switched(rest, home) ?: speed(rest, home)
                ?: thermostat(rest, home)
                ?: lockedOrClosed(rest, home)
                ?: Decline("I didn't catch a home command. Say: $trigger, turn off the kitchen light.")
        }
    }

    /** "is the garage door open", "what's the thermostat set to", "what is the status of the back door". */
    private fun asked(rest: List<String>, home: HomeDevices): HomeCommand? = when {
        rest.firstOrNull() in IS && rest.size > 2 && rest.last() in CONDITIONS ->
            home.one(rest.drop(1).dropLast(1)) { it.act(STATE, asked = rest.last()) }

        rest.take(2) in WHAT_IS -> home.one(questioned(rest.drop(2))) { it.act(STATE) }

        else -> null
    }

    /** "turn off the light", "turn the light off", "pink bedroom lights off". Both at once is neither. */
    private fun switched(rest: List<String>, home: HomeDevices): HomeCommand? {
        val verb = rest.firstOrNull() in VERBS
        val stateFirst = verb && rest.getOrNull(1) in SWITCHED
        val stateLast = rest.size > (if (verb) 2 else 1) && rest.last() in SWITCHED
        if (stateFirst == stateLast) return null
        val target = when {
            stateFirst -> rest.drop(2)
            verb -> rest.drop(1).dropLast(1)
            else -> rest.dropLast(1)
        }
        return switch(target, on = (if (stateFirst) rest[1] else rest.last()) == "on", home)
    }

    private fun switch(target: List<String>, on: Boolean, home: HomeDevices): HomeCommand {
        val room = home.lightsIn(target)
        // A device's own name wins over its room's lights: a group named "Pink Bedroom Lights" is itself.
        if (room.isNotEmpty() && home.named(target).isEmpty()) {
            val area = room.first().area
            return if (room.any { it.readOnly }) {
                Decline("I may not switch every light in $area.")
            } else {
                Act(if (on) TURN_ON else TURN_OFF, room.map { it.id }, "$area lights")
            }
        }
        return home.one(target) { device ->
            when (device.domain) {
                in SWITCHABLE -> controlled(device) { it.act(if (on) TURN_ON else TURN_OFF) }
                CLIMATE -> if (on) Decline(SAY_MODE) else controlled(device) { it.act(HVAC, value = OFF) }
                else -> Decline("I can't switch ${device.name} on or off.")
            }
        }
    }

    /** "pink bedroom fan high", "set the living room fan to medium", "turn the fan to low speed". */
    private fun speed(rest: List<String>, home: HomeDevices): HomeCommand? {
        val said = rest.dropLastWhile { it == "speed" }
        val level = SPEEDS[said.lastOrNull()] ?: return null
        val target = said.dropLast(1).dropWhile { it in VERBS || it == "set" }.dropLastWhile {
            it in
                setOf("to", "on", "at")
        }
        if (target.isEmpty()) return null
        return home.one(target) { device ->
            if (device.domain ==
                "fan"
            ) {
                controlled(device) { it.act(SPEED, value = level) }
            } else {
                Decline("${device.name} has no speeds.")
            }
        }
    }

    /** "set the thermostat to 72", "set the thermostat to cool", "cool to 72", "make it warmer". */
    private fun thermostat(rest: List<String>, home: HomeDevices): HomeCommand? {
        val to = rest.lastIndexOf("to")
        val value = if (to < 0) emptyList() else rest.drop(to + 1).filter { it !in UNITS }
        val said = value.singleOrNull()
        val first = rest.firstOrNull()
        return when {
            first == "set" && to > 1 && said != null -> climate(rest.subList(1, to), home) { setTo(it, said) }

            first in HEAT_COOL && to > 0 && said != null && degrees(said) ->
                climate(rest.subList(1, to), home) { it.act(TEMPERATURE, value = said, mode = first) }

            first == "make" && rest.size > 2 && rest.last() in STEPS ->
                climate(rest.subList(1, rest.size - 1), home) {
                    it.act(TEMPERATURE, value = STEPS.getValue(rest.last()))
                }

            else -> null
        }
    }

    private fun lockedOrClosed(rest: List<String>, home: HomeDevices): HomeCommand? = when (rest.firstOrNull()) {
        "lock" -> home.one(rest.drop(1)) { device ->
            if (device.domain ==
                "lock"
            ) {
                controlled(device) { it.act(LOCK) }
            } else {
                Decline("${device.name} is not a lock.")
            }
        }

        "close", "shut" -> home.one(rest.drop(1)) { device ->
            if (device.domain ==
                COVER
            ) {
                controlled(device) { it.act(CLOSE) }
            } else {
                Decline("I can't close ${device.name}.")
            }
        }

        else -> null
    }

    /** Any other words are a script's whole name, so the owner makes the phrases: "pink fan medium". */
    private fun ran(rest: List<String>, home: HomeDevices): HomeCommand? {
        val scripts = home.named(rest).filter { it.domain == SCRIPT }
        return when {
            // A misheard switch or question is not a script's name.
            rest.firstOrNull() in VERBS + IS || rest.take(2) in WHAT_IS || scripts.isEmpty() -> null

            scripts.size > 1 -> Decline("More than one script is called ${rest.joinToString(" ")}.")

            else -> controlled(scripts.single()) { it.act(RUN) }
        }
    }

    private const val PLEASE = "please"
    private val GREETINGS = setOf("hey", "hi", "ok", "okay")
    private const val OFF = "off"
    private const val CLIMATE = "climate"
    private const val COVER = "cover"
    private const val SCRIPT = "script"
    private val NEVER = setOf("unlock", "open")
    private val VERBS = setOf("turn", "switch")
    private val SWITCHED = setOf("on", OFF)
    private val SWITCHABLE = setOf("light", "switch", "fan")
    private val IS = setOf("is", "are")
    private val CONDITIONS = setOf("on", OFF, "open", "closed", "locked", "unlocked")

    // Whisper writes "what's" as two words once the apostrophe goes.
    private val WHAT_IS = setOf(listOf("what", "is"), listOf("what", "s"))
}

private const val SAY_MODE = "Say heat or cool, and the temperature."
private val HEAT_COOL = setOf("heat", "cool")
private val MODES = HEAT_COOL + "off"
private val UNITS = setOf("degrees", "degree", "mode")

private val SPEEDS = mapOf("low" to "low", "med" to "medium", "medium" to "medium", "high" to "high")

// ponytail: a fixed 2-degree step, right for °F; a °C home may want 1, make it a setting if that comes up.
private val STEPS = mapOf("warmer" to "+2", "cooler" to "-2")
private val IT = listOf("it")
private const val MAX_DIGITS = 3

private fun HomeDevice.act(action: String, value: String? = null, mode: String? = null, asked: String? = null) =
    Act(action, listOf(id), name, value, mode, asked)

/** Control needs the `assistant` label; `assistant_read` is for questions only. */
private fun controlled(device: HomeDevice, make: (HomeDevice) -> HomeCommand): HomeCommand =
    if (device.readOnly) Decline("You let me check ${device.name}, not change it.") else make(device)

/** Digits only: Whisper writes numbers as digits, and a spelled-out one is not guessed at. */
private fun degrees(word: String) = word.length <= MAX_DIGITS && word.all(Char::isDigit)

private fun setTo(device: HomeDevice, said: String): HomeCommand = when {
    degrees(said) -> device.act(TEMPERATURE, value = said)
    said in MODES -> device.act(HVAC, value = said)
    else -> Decline("I can set ${device.name} to a temperature, heat, cool or off.")
}

/** The thermostat [target] names, or with no name ("make it warmer"), the only one on the list. */
private fun climate(target: List<String>, home: HomeDevices, make: (HomeDevice) -> HomeCommand): HomeCommand {
    val said = target.filter { it !in ARTICLES }
    if (said.isNotEmpty() && said != IT) {
        return home.one(target) { device ->
            if (device.domain == "climate") controlled(device, make) else Decline("${device.name} is not a thermostat.")
        }
    }
    val thermostats = home.all.filter { it.domain == "climate" }
    return when (thermostats.size) {
        0 -> Decline("Your home list has no thermostat.")
        1 -> controlled(thermostats.single(), make)
        else -> Decline("Say which thermostat.")
    }
}

/** "the thermostat set to" is the thermostat; so is "the status of the thermostat". */
private fun questioned(words: List<String>): List<String> {
    val target = when {
        words.takeLast(2) == listOf("set", "to") -> words.dropLast(2)
        words.lastOrNull() == "at" -> words.dropLast(1)
        else -> words
    }.filter { it !in ARTICLES }
    return if (target.firstOrNull() in setOf("status", "state") &&
        target.getOrNull(1) == "of"
    ) {
        target.drop(2)
    } else {
        target
    }
}
