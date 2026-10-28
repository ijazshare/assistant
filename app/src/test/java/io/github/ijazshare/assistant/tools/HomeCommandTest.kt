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
import io.github.ijazshare.assistant.tools.HomeCommand.Ask
import io.github.ijazshare.assistant.tools.HomeCommand.Run
import io.github.ijazshare.assistant.tools.HomeCommand.Switch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class HomeCommandTest {

    private fun parse(transcript: String, trigger: String = "homie") = HomeCommand.parse(transcript, trigger)

    @Test
    fun theFixedShapesAreSwitches() {
        assertThat(parse("Homie, turn on the pink bedroom light.")).isEqualTo(Switch("pink bedroom light", on = true))
        assertThat(parse("Homie turn the Pink Bedroom Fan off")).isEqualTo(Switch("pink bedroom fan", on = false))
        assertThat(parse("homie switch off my desk lamp please")).isEqualTo(Switch("desk lamp", on = false))
        assertThat(parse("Homie. Please switch the porch light on.")).isEqualTo(Switch("porch light", on = true))
        // Punctuation goes the way Home Assistant's side drops it, so the names still meet.
        assertThat(parse("Homie, turn on Mum's lamp")).isEqualTo(Switch("mum s lamp", on = true))
        assertThat(parse("Homie, turn off fan 2")).isEqualTo(Switch("fan 2", on = false))
        // Only the end of the sentence can be the state, so "on" inside a name stays in it.
        assertThat(parse("Homie, turn on the on air sign")).isEqualTo(Switch("on air sign", on = true))
    }

    @Test
    fun questionsAskForTheState() {
        assertThat(parse("Homie, is the garage door open?")).isEqualTo(Ask("garage door", "open"))
        assertThat(parse("Homie, is the front door unlocked?")).isEqualTo(Ask("front door", "unlocked"))
        assertThat(parse("Homie, are the porch lights on?")).isEqualTo(Ask("porch lights", "on"))
        assertThat(parse("Homie, what's the thermostat set to?")).isEqualTo(Ask("thermostat"))
        assertThat(parse("Homie, what is the thermostat at?")).isEqualTo(Ask("thermostat"))
        assertThat(parse("Homie, what's the status of the garage door?")).isEqualTo(Ask("garage door"))
        assertThat(parse("Homie, what is the living room temperature")).isEqualTo(Ask("living room temperature"))
    }

    @Test
    fun anyOtherWordsNameAScript() {
        assertThat(parse("Homie, pink fan medium")).isEqualTo(Run("pink fan medium"))
        assertThat(parse("Homie, set fan medium please.")).isEqualTo(Run("set fan medium"))
        assertThat(parse("Homie, the usual")).isEqualTo(Run("usual"))
        assertThat(parse("Homie, lights off")).isEqualTo(Run("lights off"))
        // Only a name: Home Assistant runs it only if the owner labelled a script called exactly that.
        assertThat(parse("Homie, unlock the front door")).isEqualTo(Run("unlock the front door"))
        // Never shortened to what was asked about, which could be a script's name.
        assertThat(parse("Homie, status of the garage door")).isEqualTo(Run("status of the garage door"))
    }

    @Test
    fun anythingElseIsNotGuessedAt() {
        assertThat(parse("Homie, turn on")).isNull()
        assertThat(parse("Homie, turn on the")).isNull()
        assertThat(parse("Homie, turn the light")).isNull()
        assertThat(parse("Homie, switch to the kitchen")).isNull()
        // Both ends name a state, so which one was meant is a guess.
        assertThat(parse("Homie, turn on the light off")).isNull()
        assertThat(parse("Homie, is the garage door")).isNull()
        assertThat(parse("Homie, is open")).isNull()
        assertThat(parse("Homie, what is")).isNull()
        assertThat(parse("Homie")).isNull()
        assertThat(parse("Homie, the")).isNull()
        assertThat(parse("Homie, please")).isNull()
    }

    @Test
    fun onlyASentenceThatStartsWithTheTriggerIsForTheHome() {
        assertThat(HomeCommand.addressed("Homie, lights off", "homie")).isTrue()
        assertThat(HomeCommand.addressed("Turn on the light", "homie")).isFalse()
        assertThat(HomeCommand.addressed("Hey homie, turn on the light", "homie")).isFalse()
        assertThat(HomeCommand.addressed("Homies turn on the light", "homie")).isFalse()
        assertThat(HomeCommand.addressed("Text Sam homie turn on the light", "homie")).isFalse()
        assertThat(parse("Turn on the light")).isNull()
        // The trigger is a setting, and may be more than one word.
        assertThat(parse("Hey house, turn off the hall light", "hey house")).isEqualTo(Switch("hall light", on = false))
        assertThat(HomeCommand.addressed("Hey, turn off the hall light", "hey house")).isFalse()
        assertThat(HomeCommand.addressed("anything", "")).isFalse()
    }

    @Test
    fun theShareCarriesOnlyTheCommandAndItsId() {
        val json = Json.parseToJsonElement(Switch("pink bedroom fan", on = false).json("9f2c")).jsonObject
        assertThat(json.keys).containsExactly("do", "name", "id")
        assertThat(json.getValue("do").jsonPrimitive.content).isEqualTo("turn_off")
        assertThat(json.getValue("name").jsonPrimitive.content).isEqualTo("pink bedroom fan")
        assertThat(json.getValue("id").jsonPrimitive.content).isEqualTo("9f2c")
        // The word asked about stays on the phone; Home Assistant only reports the state.
        val asked = Json.parseToJsonElement(Ask("garage door", "open").json("1")).jsonObject
        assertThat(asked.getValue("do").jsonPrimitive.content).isEqualTo("state")
        assertThat(asked.keys).containsExactly("do", "name", "id")
        assertThat(
            Json.parseToJsonElement(Run("pink fan medium").json("2")).jsonObject.getValue("do").jsonPrimitive.content,
        )
            .isEqualTo("run")
    }

    @Test
    fun theCallCarriesTheCommandThrough() {
        listOf(
            Switch("pink bedroom light", on = true),
            Switch("fan", on = false),
            Ask("garage door", "open"),
            Ask("thermostat"),
            Run("pink fan medium"),
        )
            .forEach { assertThat(HomeCommand.of(it.toCall())).isEqualTo(it) }
        assertThat(HomeCommand.of(ToolCall(AssistantTools.HOME, emptyMap()))).isNull()
        assertThat(HomeCommand.of(ToolCall(AssistantTools.HOME, mapOf("do" to "unlock", "name" to "door")))).isNull()
        assertThat(AssistantTools.all.map { it.name }).doesNotContain(AssistantTools.HOME)
    }

    @Test
    fun onlyAConfirmedStateIsSaidAsDone() {
        val on = Switch("pink bedroom light", on = true)
        assertThat(on.said(mapOf("status" to "done"))).isEqualTo(ToolResult.ok("Pink bedroom light is on."))
        assertThat(on.said(mapOf("status" to "unsure")).success).isFalse()
        assertThat(
            on.said(mapOf("status" to "refused")).spoken,
        ).contains("no light, switch or fan called pink bedroom light")
        assertThat(on.said(null).spoken).contains("do not know if that worked")
        // Anything but the agreed words is not a yes.
        assertThat(on.said(mapOf("status" to "")).success).isFalse()
        val medium = Run("pink fan medium")
        assertThat(medium.said(mapOf("status" to "done"))).isEqualTo(ToolResult.ok("Started pink fan medium."))
        assertThat(medium.said(mapOf("status" to "refused")).spoken).contains("no script called pink fan medium")
        assertThat(medium.said(mapOf("status" to "ambiguous")).spoken).contains("more than one pink fan medium")
        assertThat(medium.said(null).spoken).contains("do not know if that worked")
    }

    @Test
    fun aQuestionIsAnsweredFromTheReportedState() {
        val open = Ask("garage door", "open")
        assertThat(
            open.said(mapOf("status" to "done", "state" to "Open")).spoken,
        ).isEqualTo("Yes, garage door is open.")
        assertThat(
            open.said(mapOf("status" to "done", "state" to "Closed")).spoken,
        ).isEqualTo("No, garage door is closed.")
        assertThat(
            open.said(mapOf("status" to "done", "state" to "Opening")).spoken,
        ).isEqualTo("Garage door is opening.")
        // Only the opposite is a no; any other state is reported, not turned into a yes or no.
        assertThat(Ask("front door", "unlocked").said(mapOf("status" to "done", "state" to "Closed")).spoken)
            .isEqualTo("Front door is closed.")
        assertThat(Ask("front door", "unlocked").said(mapOf("status" to "done", "state" to "Locked")).spoken)
            .isEqualTo("No, front door is locked.")
        assertThat(Ask("thermostat", "on").said(mapOf("status" to "done", "state" to "Heat")).spoken)
            .isEqualTo("Thermostat is heat.")
        assertThat(open.said(mapOf("status" to "ambiguous")).spoken).contains("more than one garage door")
        assertThat(open.said(mapOf("status" to "done", "state" to "Unavailable")).success).isFalse()
        assertThat(open.said(mapOf("status" to "refused")).spoken).contains("nothing called garage door")
        assertThat(
            Ask("thermostat").said(
                mapOf(
                    "status" to "done",
                    "state" to "Heat",
                    "temperature" to "21.5",
                    "target" to "22",
                ),
            ).spoken,
        ).isEqualTo("Thermostat: heat. It is 21.5 degrees, set to 22.")
        assertThat(
            Ask("living room temperature").said(mapOf("status" to "done", "state" to "21.5", "unit" to "°C")).spoken,
        )
            .isEqualTo("Living room temperature: 21.5 degrees Celsius.")
    }
}
