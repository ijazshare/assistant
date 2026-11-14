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
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.WHICH_ON

/**
 * What to say for Home Assistant's [reply] to this command, or for no reply at all. Only what the
 * reply proves: "on" only when Home Assistant saw the new state, and for a device it cannot see
 * (one-way RF), only that the command went out.
 */
fun Act.said(reply: Map<String, String>?): ToolResult {
    val status = reply?.get("status")
    return when {
        reply == null -> ToolResult.failed(
            if (asks) {
                "Home Assistant did not answer."
            } else {
                "Home Assistant did not answer, so I do not know if that worked."
            },
        )

        status == "done" -> answered(reply)

        status == "unsure" -> ToolResult.failed("I asked, but Home Assistant cannot confirm ${expected()}.")

        status == "range" -> ToolResult.failed(
            reply["low"]?.let { low ->
                reply["high"]?.let { "That is outside the ${number(low)} to ${number(it)} you allowed." }
            }
                ?: "That is outside the range you allowed.",
        )

        status == "mode" -> ToolResult.failed("$name is not on heat or cool. $SAY_HEAT_OR_COOL")

        else -> ToolResult.failed(
            "Home Assistant refused. Check the label on $name, and that your home list is current.",
        )
    }
}

/** A question, answered from what Home Assistant read, rather than a change it made. */
private val Act.asks get() = action == STATE || action == WHICH_ON

private fun Act.answered(reply: Map<String, String>): ToolResult = when (action) {
    STATE -> answer(reply)
    WHICH_ON -> lightsOn(reply)
    else -> ToolResult.ok(done(reply))
}

private const val SAY_HEAT_OR_COOL = "Say heat or cool, and the temperature."

private fun Act.done(reply: Map<String, String>): String {
    val target = number(reply["target"] ?: value.orEmpty())
    return when (action) {
        // Home Assistant cannot see a one-way device, so it only knows the command went out.
        TURN_ON, TURN_OFF -> if (reply["assumed"] ==
            "true"
        ) {
            "Turned ${onOff()} $name."
        } else {
            "$name ${isAre()} ${onOff()}."
        }

        RUN -> "Started $name."

        // ponytail: Home Assistant only knows the command went out for a one-way fan, so no read-back.
        SPEED -> "Set $name to $value."

        LOCK -> "$name is locked."

        CLOSE -> "$name is ${reply["state"].orEmpty().lowercase().ifEmpty { "closing" }}."

        HVAC -> if (value == "off") "$name is off." else "$name is set to $value."

        else -> when (reply["state"]) {
            "cool" -> "Cooling to $target."
            "heat" -> "Heating to $target."
            else -> "$name is set to $target."
        }
    }
}

/** What Home Assistant could not confirm happened. */
private fun Act.expected(): String = when (action) {
    TURN_ON, TURN_OFF -> "$name ${isAre()} ${onOff()}"
    LOCK -> "$name is locked"
    CLOSE -> "$name is closing"
    HVAC -> "$name is set to $value"
    SPEED -> "$name is on $value"
    TEMPERATURE -> if (value?.firstOrNull()?.isDigit() == true) "$name is set to $value" else "the change"
    else -> "that worked"
}

private fun Act.onOff() = if (action == TURN_ON) "on" else "off"

private fun Act.isAre() = if (entities.size > 1) "are" else "is"

private fun Act.answer(reply: Map<String, String>): ToolResult {
    val state = reply["state"].orEmpty().lowercase()
    val value = listOf(number(state), reply["unit"].orEmpty().let { SPOKEN_UNITS[it] ?: it })
        .filter(String::isNotEmpty).joinToString(" ")
    return when {
        state.isEmpty() || state in NOT_KNOWN -> ToolResult.failed(
            if (asked !=
                null
            ) {
                "Home Assistant does not know if $name is $asked right now."
            } else {
                "Home Assistant does not know $name's state right now."
            },
        )

        // A one-way device: Home Assistant only knows what it last sent, so that is all it says.
        reply["assumed"] == "true" -> ToolResult.ok("Home Assistant can't see $name, but it last set it $value.")

        state == asked -> ToolResult.ok("Yes, $name is $asked.")

        // Only the opposite is a no. "Is the front door unlocked?" answered "No" from a door
        // sensor's "closed" would be a guess about a lock nobody read.
        asked != null && OPPOSITE[asked] == state -> ToolResult.ok("No, $name is $value.")

        asked != null -> ToolResult.ok("$name is $value.")

        else -> ToolResult.ok("$name: $value.${climate(reply)}")
    }
}

/**
 * "Kitchen Light is on. Home Assistant can't see Pink Bedroom Light." A one-way (RF) light, or one
 * Home Assistant can't reach, is never counted as on or off: Home Assistant only knows what it last sent.
 */
private fun lightsOn(reply: Map<String, String>): ToolResult {
    val on = names(reply["on"])
    val blind = names(reply["blind"])
    val seen = when {
        on.size == 1 -> "${on.single()} is on."
        on.isNotEmpty() -> "${listed(on, "and")} are on."
        blind.isEmpty() -> "No lights are on."
        else -> "None of the lights Home Assistant can see are on."
    }
    return ToolResult.ok(if (blind.isEmpty()) seen else "$seen Home Assistant can't see ${listed(blind, "or")}.")
}

// The automation sends one name per line.
private fun names(text: String?) = text.orEmpty().lines().map(String::trim).filter(String::isNotEmpty)

private fun listed(names: List<String>, last: String) =
    if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " $last " + names.last()

private fun climate(reply: Map<String, String>): String {
    val now = number(reply["temperature"].orEmpty())
    val target = number(reply["target"].orEmpty())
    return when {
        now.isNotEmpty() && target.isNotEmpty() -> " It is $now degrees, set to $target."
        now.isNotEmpty() -> " It is $now degrees."
        target.isNotEmpty() -> " It is set to $target."
        else -> ""
    }
}

// Home Assistant writes whole degrees as "72.0".
private fun number(text: String) = text.removeSuffix(".0")

private val SPOKEN_UNITS = mapOf("°C" to "degrees Celsius", "°F" to "degrees Fahrenheit", "%" to "percent")
private val NOT_KNOWN = setOf("unknown", "unavailable")
private val OPPOSITE = mapOf(
    "on" to "off",
    "off" to "on",
    "open" to "closed",
    "closed" to "open",
    "locked" to "unlocked",
    "unlocked" to "locked",
)
