/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.tools

/**
 * Everything the assistant can do.
 *
 * This list is the contract in both directions: it generates the grammar the model
 * is constrained by, and it is what [ToolExecutor] switches on. Adding a capability
 * means adding it here once — there is no second place where the model's vocabulary
 * and the app's behaviour can drift apart.
 *
 * Descriptions are written for the model. They are terse and concrete because a 1B
 * model reads them as instructions, not documentation.
 */
object MachineTools {

    const val SET_ALARM = "set_alarm"
    const val SET_TIMER = "set_timer"
    const val SHOW_ALARMS = "show_alarms"
    const val CREATE_REMINDER = "create_reminder"
    const val SEND_MESSAGE = "send_message"
    const val CALL_CONTACT = "call_contact"
    const val OPEN_APP = "open_app"
    const val READ_SCREEN = "read_screen"
    const val TAKE_SCREENSHOT = "take_screenshot"
    const val TAP_TEXT = "tap_text"
    const val SCROLL = "scroll"
    const val NAVIGATE = "navigate"
    const val READ_NOTIFICATIONS = "read_notifications"
    const val ANSWER = "answer"
    const val UNSUPPORTED = "unsupported"

    val all: List<Tool> = listOf(
        Tool(
            name = SET_ALARM,
            description = "Alarm at a clock time: wake me at 7.",
            params = listOf(
                // 24-hour, which is the form this model converts to readily and
                // accurately. Splitting it into an hour plus am/pm was tried, on the
                // theory that a small model should not be asked to convert at all, and
                // was worse: it still reached for 18, and a 1..12 range simply truncated
                // that to 12. The range remains, so an impossible hour stays impossible.
                ToolParam(
                    "hour",
                    ParamType.INTEGER,
                    "Hour in 24-hour time, 0 to 23. Six in the evening is 18.",
                    required = true,
                    range = 0..23,
                ),
                ToolParam(
                    "minute",
                    ParamType.INTEGER,
                    "Minutes past the hour, 0 if not said.",
                    range = 0..59,
                ),
                ToolParam("label", ParamType.STRING, "What the alarm is for."),
            ),
        ),
        Tool(
            name = SET_TIMER,
            description = "Countdown for a length of time.",
            params = listOf(
                // Each unit is reported as spoken and added up in Kotlin. Asked for a
                // total, the model copied the number from whichever example looked
                // nearest — "ten minute timer" came back as 180 because an example used
                // three minutes.
                ToolParam("hours", ParamType.INTEGER, "Number of hours said, if any.", range = 0..23),
                ToolParam("minutes", ParamType.INTEGER, "Number of minutes said, if any.", range = 0..59),
                ToolParam("seconds", ParamType.INTEGER, "Number of seconds said, if any.", range = 0..59),
                ToolParam("label", ParamType.STRING, "What the timer is for."),
            ),
        ),
        Tool(
            name = SHOW_ALARMS,
            description = "List alarms already set.",
        ),
        Tool(
            name = CREATE_REMINDER,
            description = "Remember a task, optionally at a time.",
            params = listOf(
                ToolParam("task", ParamType.STRING, "What to be reminded of.", required = true),
                ToolParam(
                    "hour",
                    ParamType.INTEGER,
                    "Hour in 24-hour time, if a time was given. Six in the evening is 18.",
                    range = 0..23,
                ),
                ToolParam(
                    "minute",
                    ParamType.INTEGER,
                    "Minutes past the hour, if a time was given.",
                    range = 0..59,
                ),
                ToolParam("tomorrow", ParamType.BOOLEAN, "True if it is for tomorrow rather than today."),
            ),
            requires = ToolCapability.EXACT_ALARM,
        ),
        Tool(
            name = SEND_MESSAGE,
            description = "Text or message someone.",
            params = listOf(
                ToolParam("recipient", ParamType.STRING, "Contact name or phone number.", required = true),
                ToolParam("body", ParamType.STRING, "The message to send.", required = true),
            ),
            requires = ToolCapability.SMS,
        ),
        Tool(
            name = CALL_CONTACT,
            description = "Call, phone or ring someone.",
            params = listOf(
                ToolParam("recipient", ParamType.STRING, "Contact name or phone number.", required = true),
            ),
            requires = ToolCapability.CONTACTS,
        ),
        Tool(
            name = OPEN_APP,
            description = "Open an app by name.",
            params = listOf(
                ToolParam("app", ParamType.STRING, "The app's name as the user said it.", required = true),
            ),
        ),
        Tool(
            name = READ_SCREEN,
            // The two screen tools both contain the token "screen", so "screen" cannot be
            // what tells them apart and the rest of the wording has to. What the user gets
            // back is the distinction: speech now, or a file for later. The vocabularies
            // are kept disjoint — speak/words/read/say appear only here, picture/gallery/
            // screenshot/capture/save only in take_screenshot — so the word the user
            // actually said lands in exactly one place. The closing negative follows
            // navigate's "Never scrolling.", the other colliding pair in this list.
            description = "Speak the words on screen, or answer a question about them: " +
                "what does this say, read this to me, summarise this, what is this error. " +
                "Never saves a picture.",
            params = listOf(
                ToolParam(
                    "question",
                    ParamType.STRING,
                    "What was asked about the screen. Omit to read it out word for word.",
                ),
            ),
            requires = ToolCapability.ACCESSIBILITY,
        ),
        Tool(
            name = TAKE_SCREENSHOT,
            description = "Save a picture of the screen in the gallery: " +
                "take a screenshot, screenshot this, capture the screen.",
            // Deliberately no parameters. A screenshot has no arguments, and every
            // optional slot is one more place a small model can invent a value — which is
            // exactly how a mumbled "50" became a three minute timer.
            requires = ToolCapability.ACCESSIBILITY,
        ),
        Tool(
            name = TAP_TEXT,
            description = "Tap an item by its label.",
            params = listOf(
                ToolParam("label", ParamType.STRING, "The visible text to tap.", required = true),
            ),
            requires = ToolCapability.ACCESSIBILITY,
        ),
        Tool(
            name = SCROLL,
            description = "Scroll the page: scroll down, scroll up.",
            params = listOf(
                ToolParam(
                    "direction",
                    ParamType.ENUM,
                    "Which way to scroll.",
                    required = true,
                    values = listOf("up", "down"),
                ),
            ),
            requires = ToolCapability.ACCESSIBILITY,
        ),
        Tool(
            name = NAVIGATE,
            description = "Press back, home, recents or quick settings. Never scrolling.",
            params = listOf(
                ToolParam(
                    "target",
                    ParamType.ENUM,
                    "Which system action to perform.",
                    required = true,
                    values = listOf("back", "home", "recents", "notifications", "quick_settings"),
                ),
            ),
            requires = ToolCapability.ACCESSIBILITY,
        ),
        Tool(
            name = READ_NOTIFICATIONS,
            description = "Waiting notifications: what did I miss.",
            requires = ToolCapability.NOTIFICATION_ACCESS,
        ),
        Tool(
            name = ANSWER,
            description = "Answer a general question: a fact, arithmetic, or the time elsewhere. " +
                "Not the screen.",
            params = listOf(
                ToolParam("text", ParamType.STRING, "The answer, one or two sentences.", required = true),
            ),
        ),
        Tool(
            name = UNSUPPORTED,
            description = "Nothing else fits: errands this phone cannot run, like booking or ordering.",
            params = listOf(
                ToolParam("reason", ParamType.STRING, "Briefly, what was asked for.", required = true),
            ),
        ),
    )

    fun byName(name: String): Tool? = all.firstOrNull { it.name == name }

    /** The grammar the model is sampled against. Built once; it never changes at runtime. */
    val grammar: String by lazy { ToolGrammar.build(all) }
}
