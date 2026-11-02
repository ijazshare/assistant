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

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What [HomeGrammar] made of a home command: something to hand to Home Assistant, or why not. */
sealed interface HomeCommand {
    fun toCall(): ToolCall

    /**
     * An [action] on [entities] (one, or every light in a room), said as [name]. [value] is a
     * temperature ("72", "+2"), a mode
     * or a fan speed (low, medium, high); [mode] goes with a temperature; [asked] is the state a
     * question asked about, which stays on the phone.
     */
    data class Act(
        val action: String,
        val entities: List<String>,
        val name: String,
        val value: String? = null,
        val mode: String? = null,
        val asked: String? = null,
    ) : HomeCommand {
        override fun toCall() = ToolCall(
            AssistantTools.HOME,
            buildMap {
                put(DO, action)
                put(ENTITY, entities.joinToString(","))
                put(NAME, name)
                value?.let { put(VALUE, it) }
                mode?.let { put(MODE, it) }
                asked?.let { put(ASKED, it) }
            },
        )

        /** The share's text: only the entity ids and what to do. [id] ties the reply to this command. */
        fun json(id: String): String = buildJsonObject {
            put(DO, action)
            putJsonArray(ENTITY) { entities.forEach { add(it) } }
            put("id", id)
            value?.let { put(VALUE, it) }
            mode?.let { put(MODE, it) }
        }.toString()
    }

    /** Nothing is sent; Assistant says [reason] instead. */
    data class Decline(val reason: String) : HomeCommand {
        override fun toCall() = ToolCall(AssistantTools.HOME, mapOf(DECLINED to reason))
    }

    companion object {
        const val STATE = "state"
        const val TURN_ON = "turn_on"
        const val TURN_OFF = "turn_off"
        const val RUN = "run"
        const val LOCK = "lock"
        const val CLOSE = "close"
        const val HVAC = "hvac"
        const val TEMPERATURE = "temperature"
        const val SPEED = "speed"

        /** The command a [toCall] call carries, or null for a call the words did not make. */
        fun of(call: ToolCall): HomeCommand? {
            call.string(DECLINED)?.let { return Decline(it) }
            val action = call.string(DO)?.takeIf { it in ACTIONS }
            val entities = call.string(ENTITY)?.split(",").orEmpty().filter(String::isNotBlank)
            val name = call.string(NAME)
            return if (action == null || entities.isEmpty() || name == null) {
                null
            } else {
                Act(action, entities, name, call.string(VALUE), call.string(MODE), call.string(ASKED))
            }
        }

        private val ACTIONS = setOf(STATE, TURN_ON, TURN_OFF, RUN, LOCK, CLOSE, HVAC, TEMPERATURE, SPEED)
        private const val DO = "do"
        private const val ENTITY = "entity"
        private const val NAME = "name"
        private const val VALUE = "value"
        private const val MODE = "mode"
        private const val ASKED = "asked"
        private const val DECLINED = "declined"
    }
}
