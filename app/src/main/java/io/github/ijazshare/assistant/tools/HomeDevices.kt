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

/**
 * One device or script the owner labelled for Assistant, as their Home Assistant list names it.
 * Home Assistant checks the label again before acting; this only tells Assistant what words mean.
 */
data class HomeDevice(val id: String, val name: String, val area: String? = null, val readOnly: Boolean = false) {
    val domain: String get() = id.substringBefore('.')

    /** "Lamp in Den", so two lamps can be told apart. */
    val placed: String get() = area?.let { "$name in $it" } ?: name
}

/**
 * The owner's list, pasted from Home Assistant into the Context screen:
 *
 *     ## Pink Bedroom
 *     - Pink Bedroom Light (light.pink_bedroom_light)
 *     - Window (binary_sensor.pink_bedroom_window) read only
 *
 * A device is named by its whole name, or by its name and its room in either order: "pink bedroom
 * light", "ceiling light in the pink bedroom". Never by part of a name, since "bedroom light" could
 * be the wrong bedroom's.
 */
class HomeDevices(val all: List<HomeDevice>) {

    /** The devices [words] name exactly: one, several when two share a name, or none. */
    fun named(words: List<String>): List<HomeDevice> {
        val said = withoutArticles(words)
        // A name of nothing but punctuation would otherwise answer to silence.
        if (said.isEmpty()) return emptyList()
        return all.filter { device -> said in forms(device) }
    }

    /** The one device [words] name, made into a command by [make], or why there is not one. */
    fun one(words: List<String>, make: (HomeDevice) -> HomeCommand): HomeCommand {
        val said = withoutArticles(words)
        val found = named(said)
        return when {
            said.isEmpty() -> HomeCommand.Decline("Say which one.")

            found.isEmpty() -> HomeCommand.Decline("Your home list has nothing called ${said.joinToString(" ")}.")

            // Two in one room with one name can't be told apart by voice at all.
            found.map { it.placed }.distinct().size < found.size -> HomeCommand.Decline(
                "Two things on your home list are called ${found.first().placed}. Rename one in Home Assistant.",
            )

            found.size > 1 -> HomeCommand.Decline(
                "Which ${said.joinToString(" ")}? ${found.joinToString(", ") { it.placed }}.",
            )

            else -> make(found.single())
        }
    }

    /** "pink bedroom lights", "lights in the pink bedroom": every light listed in that room. */
    fun lightsIn(words: List<String>): List<HomeDevice> {
        val said = withoutArticles(words)
        val room = when {
            said.size > 1 && said.last() == LIGHTS -> said.dropLast(1)
            said.size > 2 && said.take(2) == listOf(LIGHTS, IN) -> said.drop(2)
            else -> return emptyList()
        }
        return all.filter { it.domain == LIGHT && it.area?.let(::words)?.let(::withoutArticles) == room }
    }

    companion object {
        /** Lower case with punctuation dropped, the same way for what was said and what the list says. */
        fun words(text: String): List<String> = text.lowercase().split(NOT_WORD).filter(String::isNotEmpty)

        /** Reads the list Home Assistant printed. Any other line, like the file's own guidance, is skipped. */
        fun parse(text: String): HomeDevices {
            var room: String? = null
            val devices = mutableListOf<HomeDevice>()
            for (line in text.lines().map(String::trim)) {
                val area = AREA.matchEntire(line)
                val device = DEVICE.matchEntire(line)
                when {
                    area != null -> room = area.groupValues[1].trim().takeUnless { it == NO_AREA }

                    device != null -> devices += HomeDevice(
                        id = device.groupValues[2],
                        name = device.groupValues[1].trim(),
                        area = room,
                        readOnly = device.groupValues[3].isNotEmpty(),
                    )
                }
            }
            return HomeDevices(devices)
        }

        // The room is said once: "pink bedroom light" is the room's "light" whether or not the name repeats it.
        private fun forms(device: HomeDevice): List<List<String>> {
            val name = withoutArticles(words(device.name))
            val room = device.area?.let { withoutArticles(words(it)) }.orEmpty()
            if (room.isEmpty()) return listOf(name)
            val short = if (name.size > room.size && name.take(room.size) == room) name.drop(room.size) else name
            return listOf(name, room + short, short + IN + room)
        }

        private fun withoutArticles(words: List<String>) = words.filter { it !in ARTICLES }

        val ARTICLES = setOf("the", "my")
        private const val LIGHT = "light"
        private const val LIGHTS = "lights"
        private const val IN = "in"
        private const val NO_AREA = "No area"
        private val NOT_WORD = Regex("[^a-z0-9]+")
        private val AREA = Regex("""##\s+(.+)""")
        private val DEVICE = Regex("""[-*]\s+(.+?)\s+\(([a-z_]+\.[a-z0-9_]+)\)(\s+read only)?""")
    }
}
