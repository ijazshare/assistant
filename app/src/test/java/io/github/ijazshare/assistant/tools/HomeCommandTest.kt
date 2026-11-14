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

import com.google.common.truth.Truth.assertThat
import io.github.ijazshare.assistant.tools.HomeCommand.Act
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.CLOSE
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.HVAC
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.LOCK
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.RUN
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.STATE
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.TEMPERATURE
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.TURN_OFF
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.TURN_ON
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.WHICH_ON
import io.github.ijazshare.assistant.tools.HomeCommand.Decline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class HomeCommandTest {

    private val light = Act(TURN_ON, listOf("light.pink_bedroom_light"), "Pink Bedroom Light")
    private val thermostat = Act(TEMPERATURE, listOf("climate.thermostat"), "Thermostat", "72", mode = "cool")

    private fun done(vararg extras: Pair<String, String>) = mapOf("status" to "done", *extras)

    @Test
    fun theShareCarriesOnlyEntityIdsAndWhatToDo() {
        val json = Json.parseToJsonElement(light.json("i1")).jsonObject
        assertThat(json.keys).containsExactly("do", "entity", "id")
        assertThat(json.getValue("do").jsonPrimitive.content).isEqualTo("turn_on")
        assertThat(
            json.getValue("entity").jsonArray.map {
                it.jsonPrimitive.content
            },
        ).containsExactly("light.pink_bedroom_light")
        val set = Json.parseToJsonElement(thermostat.json("i2")).jsonObject
        assertThat(set.getValue("value").jsonPrimitive.content).isEqualTo("72")
        assertThat(set.getValue("mode").jsonPrimitive.content).isEqualTo("cool")
        // The name and the word asked about stay on the phone.
        val asked = Act(STATE, listOf("cover.garage_door"), "Garage Door", asked = "open")
        assertThat(Json.parseToJsonElement(asked.json("i3")).jsonObject.keys).containsExactly("do", "entity", "id")
    }

    @Test
    fun theCallCarriesTheCommandThrough() {
        listOf(
            light,
            thermostat,
            Act(TURN_OFF, listOf("light.a", "light.b"), "Den lights"),
            Act(STATE, listOf("cover.garage_door"), "Garage Door", asked = "open"),
            Decline("Say which one."),
        ).forEach { assertThat(HomeCommand.of(it.toCall())).isEqualTo(it) }
        assertThat(HomeCommand.of(ToolCall(AssistantTools.HOME, emptyMap()))).isNull()
        assertThat(
            HomeCommand.of(
                ToolCall(
                    AssistantTools.HOME,
                    mapOf(
                        "do" to "unlock",
                        "entity" to "lock.a",
                        "name" to "A",
                    ),
                ),
            ),
        )
            .isNull()
        assertThat(AssistantTools.all.map { it.name }).doesNotContain(AssistantTools.HOME)
    }

    @Test
    fun onlyWhatTheReplyProvesIsSaid() {
        assertThat(light.said(done()).spoken).isEqualTo("Pink Bedroom Light is on.")
        // One-way RF: Home Assistant only knows the command went out.
        assertThat(light.said(done("assumed" to "true")).spoken).isEqualTo("Turned on Pink Bedroom Light.")
        assertThat(
            Act(TURN_OFF, listOf("light.a", "light.b"), "Den lights").said(done()).spoken,
        ).isEqualTo("Den lights are off.")
        assertThat(light.said(mapOf("status" to "unsure")).spoken)
            .isEqualTo("I asked, but Home Assistant cannot confirm Pink Bedroom Light is on.")
        assertThat(light.said(mapOf("status" to "refused")).spoken).startsWith("Home Assistant refused.")
        assertThat(light.said(mapOf("status" to "")).success).isFalse()
        assertThat(light.said(null).spoken).contains("do not know if that worked")
        assertThat(
            Act(RUN, listOf("script.a"), "Pink fan medium").said(done()).spoken,
        ).isEqualTo("Started Pink fan medium.")
        assertThat(Act(LOCK, listOf("lock.a"), "Front Door").said(done()).spoken).isEqualTo("Front Door is locked.")
        assertThat(Act(CLOSE, listOf("cover.a"), "Garage Door").said(done("state" to "Closing")).spoken)
            .isEqualTo("Garage Door is closing.")
        assertThat(
            Act(HVAC, listOf("climate.a"), "Thermostat", "off").said(done()).spoken,
        ).isEqualTo("Thermostat is off.")
        assertThat(
            Act(HVAC, listOf("climate.a"), "Thermostat", "cool").said(done()).spoken,
        ).isEqualTo("Thermostat is set to cool.")
    }

    @Test
    fun theThermostatSaysWhatItIsDoing() {
        assertThat(thermostat.said(done("target" to "72.0", "state" to "cool")).spoken).isEqualTo("Cooling to 72.")
        assertThat(
            Act(
                TEMPERATURE,
                listOf("climate.a"),
                "Thermostat",
                "+2",
            ).said(done("target" to "70", "state" to "heat")).spoken,
        ).isEqualTo("Heating to 70.")
        assertThat(thermostat.said(mapOf("status" to "range", "low" to "60", "high" to "85.0")).spoken)
            .isEqualTo("That is outside the 60 to 85 you allowed.")
        assertThat(thermostat.said(mapOf("status" to "mode")).spoken).startsWith("Thermostat is not on heat or cool.")
        assertThat(thermostat.said(mapOf("status" to "unsure")).spoken)
            .isEqualTo("I asked, but Home Assistant cannot confirm Thermostat is set to 72.")
    }

    @Test
    fun aQuestionIsAnsweredFromTheReportedState() {
        val open = Act(STATE, listOf("cover.garage_door"), "Garage Door", asked = "open")
        assertThat(open.said(done("state" to "Open")).spoken).isEqualTo("Yes, Garage Door is open.")
        assertThat(open.said(done("state" to "Closed")).spoken).isEqualTo("No, Garage Door is closed.")
        assertThat(open.said(done("state" to "Opening")).spoken).isEqualTo("Garage Door is opening.")
        // Only the opposite is a no; any other state is reported, not turned into a yes or no.
        val unlocked = Act(STATE, listOf("binary_sensor.front"), "Front Door", asked = "unlocked")
        assertThat(unlocked.said(done("state" to "Closed")).spoken).isEqualTo("Front Door is closed.")
        assertThat(unlocked.said(done("state" to "Locked")).spoken).isEqualTo("No, Front Door is locked.")
        // A one-way device: only what Home Assistant last sent.
        val lamp = Act(STATE, listOf("light.a"), "Pink Bedroom Light", asked = "on")
        assertThat(lamp.said(done("state" to "On", "assumed" to "true")).spoken)
            .isEqualTo("Home Assistant can't see Pink Bedroom Light, but it last set it on.")
        assertThat(open.said(done("state" to "Unavailable")).success).isFalse()
        assertThat(open.said(null).spoken).isEqualTo("Home Assistant did not answer.")
        assertThat(
            Act(STATE, listOf("climate.t"), "Thermostat").said(
                done("state" to "Heat", "temperature" to "21.5", "target" to "22.0"),
            ).spoken,
        ).isEqualTo("Thermostat: heat. It is 21.5 degrees, set to 22.")
        assertThat(
            Act(STATE, listOf("sensor.k"), "Kitchen Temperature").said(done("state" to "21.5", "unit" to "°C")).spoken,
        ).isEqualTo("Kitchen Temperature: 21.5 degrees Celsius.")
    }

    @Test
    fun whichLightsAreOnSaysOnlyWhatHomeAssistantCanSee() {
        val lights = Act(WHICH_ON, listOf("light.a", "light.b", "switch.c"), "lights")
        assertThat(HomeCommand.of(lights.toCall())).isEqualTo(lights)
        assertThat(lights.said(done()).spoken).isEqualTo("No lights are on.")
        assertThat(lights.said(done("on" to "Kitchen Light")).spoken).isEqualTo("Kitchen Light is on.")
        assertThat(lights.said(done("on" to "Kitchen Light\nDen Lamp\nHall")).spoken)
            .isEqualTo("Kitchen Light, Den Lamp and Hall are on.")
        assertThat(
            lights.said(done("on" to "Kitchen Light", "blind" to "Pink Bedroom Light\nLiving Room Light")).spoken,
        )
            .isEqualTo("Kitchen Light is on. Home Assistant can't see Pink Bedroom Light or Living Room Light.")
        assertThat(lights.said(done("blind" to "Pink Bedroom Light")).spoken)
            .isEqualTo("None of the lights Home Assistant can see are on. Home Assistant can't see Pink Bedroom Light.")
        assertThat(lights.said(null).spoken).isEqualTo("Home Assistant did not answer.")
        assertThat(lights.said(mapOf("status" to "refused")).success).isFalse()
    }
}
