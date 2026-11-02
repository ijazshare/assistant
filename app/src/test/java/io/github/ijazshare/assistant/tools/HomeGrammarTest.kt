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
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.SPEED
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.STATE
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.TEMPERATURE
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.TURN_OFF
import io.github.ijazshare.assistant.tools.HomeCommand.Companion.TURN_ON
import io.github.ijazshare.assistant.tools.HomeCommand.Decline
import org.junit.Test

class HomeGrammarTest {

    // What the README's template prints in Home Assistant, under the file's own guidance.
    private val home = HomeDevices.parse(
        """
        # Home Assistant devices

        > Paste what the template prints below.
        > ## Not A Room
        > - Not A Device (light.not_a_device)
        ## Pink Bedroom
        - Pink Bedroom Light (light.pink_bedroom_light)
        - Pink Bedroom Fan (fan.pink_bedroom_fan)
        - Lamp (light.pink_lamp)
        ## Den
        - Lamp (light.den_lamp)
        - Ceiling Light (light.den_ceiling)
        - Window (binary_sensor.den_window) read only
        ## Hallway
        - Front Door (lock.front_door)
        - Back Door (lock.back_door) read only
        - Thermostat (climate.thermostat)
        ## Garage
        - Garage Door (cover.garage_door)
        ## Kitchen
        - Kitchen Temperature (sensor.kitchen_temperature) read only
        ## No area
        - Pink fan medium (script.pink_fan_medium)
        - Movie on (script.movie_on)
        """.trimIndent(),
    )

    private fun read(said: String, list: HomeDevices = home) = HomeGrammar.read(said, "homie", list)

    private fun declined(said: String, list: HomeDevices = home) = (read(said, list) as Decline).reason

    private fun act(action: String, id: String, name: String) = Act(action, listOf(id), name)

    private val pinkLight = act(TURN_ON, "light.pink_bedroom_light", "Pink Bedroom Light")
    private val ceiling = act(TURN_OFF, "light.den_ceiling", "Ceiling Light")
    private val garage = act(STATE, "cover.garage_door", "Garage Door")
    private val thermostat = act(TEMPERATURE, "climate.thermostat", "Thermostat")
    private val mode = act(HVAC, "climate.thermostat", "Thermostat")

    @Test
    fun theListIsReadAsHomeAssistantPrintsIt() {
        assertThat(home.all).hasSize(13)
        assertThat(home.all.first())
            .isEqualTo(HomeDevice("light.pink_bedroom_light", "Pink Bedroom Light", "Pink Bedroom"))
        assertThat(home.all.single { it.id == "binary_sensor.den_window" }.readOnly).isTrue()
        assertThat(home.all.single { it.id == "script.movie_on" }.area).isNull()
        // The id is what ends the line, so a name may hold brackets of its own.
        assertThat(HomeDevices.parse("- TV (Den) (media_player.tv)").all.single().name).isEqualTo("TV (Den)")
    }

    @Test
    fun aDeviceIsItsNameOrItsNameAndRoom() {
        assertThat(read("Homie, turn on the pink bedroom light.")).isEqualTo(pinkLight)
        assertThat(read("Homie, turn on the light in the pink bedroom")).isEqualTo(pinkLight)
        assertThat(read("Homie turn the Pink Bedroom Light on please")).isEqualTo(pinkLight)
        assertThat(read("Homie, pink bedroom light on")).isEqualTo(pinkLight)
        assertThat(read("Homie, turn off the ceiling light")).isEqualTo(ceiling)
        assertThat(read("Homie, switch off the den ceiling light")).isEqualTo(ceiling)
        assertThat(read("Homie, turn off the ceiling light in the den")).isEqualTo(ceiling)
        assertThat(read("Homie, turn on the lamp in the den")).isEqualTo(act(TURN_ON, "light.den_lamp", "Lamp"))
        assertThat(read("Homie, turn off the pink bedroom fan"))
            .isEqualTo(act(TURN_OFF, "fan.pink_bedroom_fan", "Pink Bedroom Fan"))
    }

    @Test
    fun lightsInARoomAreAllOfThem() {
        assertThat(read("Homie, pink bedroom lights off"))
            .isEqualTo(Act(TURN_OFF, listOf("light.pink_bedroom_light", "light.pink_lamp"), "Pink Bedroom lights"))
        assertThat(read("Homie, turn on the lights in the den"))
            .isEqualTo(Act(TURN_ON, listOf("light.den_lamp", "light.den_ceiling"), "Den lights"))
        val partly = HomeDevices.parse("## Den\n- Lamp (light.den_lamp)\n- Shelf (light.den_shelf) read only")
        assertThat(declined("Homie, den lights off", partly)).isEqualTo("I may not switch every light in Den.")
    }

    @Test
    fun nothingIsGuessed() {
        assertThat(declined("Homie, turn on the lamp")).isEqualTo("Which lamp? Lamp in Pink Bedroom, Lamp in Den.")
        // Part of a name is a guess: it could be the wrong bedroom's.
        assertThat(declined("Homie, turn on the bedroom light"))
            .isEqualTo("Your home list has nothing called bedroom light.")
        assertThat(declined("Homie, turn on the light")).isEqualTo("Your home list has nothing called light.")
        // Words left over change what was meant, so they are never dropped.
        assertThat(declined("Homie, turn on the pink bedroom light in 10 minutes"))
            .isEqualTo("Your home list has nothing called pink bedroom light in 10 minutes.")
        assertThat(declined("Homie, turn on")).isEqualTo("Say which one.")
        assertThat(declined("Homie, turn on the light off")).startsWith("I didn't catch a home command.")
        assertThat(declined("Homie, dim the kitchen light")).startsWith("I didn't catch a home command.")
        assertThat(declined("Homie"))
            .isEqualTo("I didn't catch a home command. Say: homie, turn off the kitchen light.")
        val twins = HomeDevices.parse("## Den\n- Lamp (light.a)\n- Lamp (light.b)")
        assertThat(declined("Homie, turn on the den lamp", twins))
            .isEqualTo("Two things on your home list are called Lamp in Den. Rename one in Home Assistant.")
        assertThat(declined("Homie, turn on the lamp", HomeDevices(emptyList())))
            .isEqualTo("Paste your Home Assistant device list into Context first.")
    }

    @Test
    fun questionsAskForTheState() {
        assertThat(read("Homie, is the garage door open?")).isEqualTo(garage.copy(asked = "open"))
        assertThat(read("Homie, is the back door locked?"))
            .isEqualTo(act(STATE, "lock.back_door", "Back Door").copy(asked = "locked"))
        assertThat(read("Homie, is the den window open"))
            .isEqualTo(act(STATE, "binary_sensor.den_window", "Window").copy(asked = "open"))
        assertThat(read("Homie, what's the thermostat set to?")).isEqualTo(thermostat.copy(action = STATE))
        assertThat(read("Homie, what's the status of the garage door?")).isEqualTo(garage)
        val kitchen = act(STATE, "sensor.kitchen_temperature", "Kitchen Temperature")
        assertThat(read("Homie, what is the kitchen temperature")).isEqualTo(kitchen)
        assertThat(read("Homie, what's the temperature in the kitchen?")).isEqualTo(kitchen)
    }

    @Test
    fun theThermostat() {
        assertThat(read("Homie, set the thermostat to 72")).isEqualTo(thermostat.copy(value = "72"))
        assertThat(read("Homie, set the thermostat to 72 degrees.")).isEqualTo(thermostat.copy(value = "72"))
        assertThat(read("Homie, set it to 68")).isEqualTo(thermostat.copy(value = "68"))
        assertThat(read("Homie, cool to 72")).isEqualTo(thermostat.copy(value = "72", mode = "cool"))
        assertThat(read("Homie, heat the thermostat to 68 degrees"))
            .isEqualTo(thermostat.copy(value = "68", mode = "heat"))
        assertThat(read("Homie, set the thermostat to cool")).isEqualTo(mode.copy(value = "cool"))
        assertThat(read("Homie, set the thermostat to heat mode")).isEqualTo(mode.copy(value = "heat"))
        assertThat(read("Homie, turn off the thermostat")).isEqualTo(mode.copy(value = "off"))
        assertThat(read("Homie, make it warmer")).isEqualTo(thermostat.copy(value = "+2"))
        assertThat(read("Homie, make the thermostat cooler please")).isEqualTo(thermostat.copy(value = "-2"))
        assertThat(declined("Homie, turn on the thermostat")).isEqualTo("Say heat or cool, and the temperature.")
        assertThat(declined("Homie, set the thermostat to 1000")).startsWith("I can set Thermostat to")
        assertThat(declined("Homie, set the pink bedroom light to 50"))
            .isEqualTo("Pink Bedroom Light is not a thermostat.")
        // A spelled-out number is not guessed at.
        assertThat(declined("Homie, set the thermostat to seventy two")).startsWith("I didn't catch a home command.")
        val two = HomeDevices.parse("- Up (climate.up)\n- Down (climate.down)")
        assertThat(declined("Homie, cool to 70", two)).isEqualTo("Say which thermostat.")
    }

    @Test
    fun locksAndCoversOnlyLockAndClose() {
        assertThat(read("Homie, lock the front door")).isEqualTo(act(LOCK, "lock.front_door", "Front Door"))
        assertThat(read("Homie, close the garage door")).isEqualTo(garage.copy(action = CLOSE))
        assertThat(declined("Homie, unlock the front door")).isEqualTo("I don't unlock or open anything by voice.")
        assertThat(declined("Homie, open the garage door")).isEqualTo("I don't unlock or open anything by voice.")
        assertThat(declined("Homie, lock the back door")).isEqualTo("You let me check Back Door, not change it.")
        assertThat(declined("Homie, lock the pink bedroom light")).isEqualTo("Pink Bedroom Light is not a lock.")
        assertThat(declined("Homie, turn off the front door")).isEqualTo("I can't switch Front Door on or off.")
    }

    @Test
    fun theOwnersOwnPhrasesAreScripts() {
        val medium = act(RUN, "script.pink_fan_medium", "Pink fan medium")
        assertThat(read("Homie, pink fan medium")).isEqualTo(medium)
        assertThat(read("Homie, pink fan medium please.")).isEqualTo(medium)
        // A script's own name wins over a shape it happens to fit.
        assertThat(read("Homie, movie on")).isEqualTo(act(RUN, "script.movie_on", "Movie on"))
        assertThat(declined("Homie, turn on pink fan medium")).isEqualTo("I can't switch Pink fan medium on or off.")
    }

    @Test
    fun onlyASentenceThatStartsWithTheTriggerIsForTheHome() {
        assertThat(HomeGrammar.addressed("Homie, lights off", "homie")).isTrue()
        assertThat(HomeGrammar.addressed("Turn on the light", "homie")).isFalse()
        // The owner says "Hey homie" (query log, 2026-11-02), so an opening greeting is allowed.
        assertThat(HomeGrammar.addressed("Homies turn on the light", "homie")).isFalse()
        assertThat(HomeGrammar.addressed("Text Sam homie turn on the light", "homie")).isFalse()
        // The trigger is a setting, and may be more than one word.
        assertThat(HomeGrammar.read("Hey house, turn off the ceiling light", "hey house", home)).isEqualTo(ceiling)
        assertThat(HomeGrammar.addressed("Hey, turn off the hall light", "hey house")).isFalse()
        assertThat(HomeGrammar.addressed("anything", "")).isFalse()
    }

    @Test
    fun aFanSpeedIsSetByName() {
        val fan = { level: String -> Act(SPEED, listOf("fan.pink_bedroom_fan"), "Pink Bedroom Fan", level) }
        assertThat(read("homie pink bedroom fan high")).isEqualTo(fan("high"))
        assertThat(read("Homie, set the pink bedroom fan to medium.")).isEqualTo(fan("medium"))
        assertThat(read("homie turn the pink bedroom fan to low speed")).isEqualTo(fan("low"))
        assertThat(read("homie pink bedroom fan on med")).isEqualTo(fan("medium"))
        assertThat(
            read("homie pink bedroom fan off"),
        ).isEqualTo(Act(TURN_OFF, listOf("fan.pink_bedroom_fan"), "Pink Bedroom Fan"))
        // The owner's own script of that name still wins.
        assertThat(read("homie pink fan medium")).isEqualTo(act(RUN, "script.pink_fan_medium", "Pink fan medium"))
        assertThat(declined("homie pink bedroom light high")).isEqualTo("Pink Bedroom Light has no speeds.")
        assertThat(read("homie high")).isInstanceOf(Decline::class.java)
    }

    @Test
    fun aGreetingBeforeTheTriggerWordIsAllowed() {
        assertThat(HomeGrammar.addressed("Hey homie, turn off the pink bedroom light", "homie")).isTrue()
        assertThat(HomeGrammar.addressed("Okay homie turn off the pink bedroom light", "homie")).isTrue()
        assertThat(read("Hey, homie. Turn on the pink bedroom light.")).isEqualTo(pinkLight)
        assertThat(HomeGrammar.addressed("Hey Jarvis, lights off", "hey jarvis")).isTrue()
        assertThat(HomeGrammar.addressed("Call me, turn off the light", "homie")).isFalse()
    }
}
