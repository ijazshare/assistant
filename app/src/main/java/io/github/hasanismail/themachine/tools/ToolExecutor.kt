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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import io.github.hasanismail.themachine.services.MachineAccessibilityService
import io.github.hasanismail.themachine.services.MachineNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Carries out a tool call.
 *
 * Every branch has to answer two questions honestly: can this actually be done right
 * now, and what should be said aloud. A tool that quietly fails is worse than one that
 * refuses, because the user walks away believing the alarm is set.
 */
class ToolExecutor(
    private val context: Context,
    private val reminders: ReminderStore,
    private val contacts: ContactLookup,
) {

    /**
     * Every tool the model can name, mapped to the code that carries it out.
     *
     * A table rather than a `when`, so that adding a tool is one entry next to its
     * declaration in [MachineTools] instead of a new branch in a function that had
     * already grown past the point where anyone could see all of it at once.
     */
    private val handlers: Map<String, suspend (ToolCall) -> ToolResult> = mapOf(
        MachineTools.SET_ALARM to { call -> setAlarm(call) },
        MachineTools.SET_TIMER to { call -> setTimer(call) },
        MachineTools.SHOW_ALARMS to { _ -> showAlarms() },
        MachineTools.CREATE_REMINDER to { call -> createReminder(call) },
        MachineTools.SEND_MESSAGE to { call -> sendMessage(call) },
        MachineTools.CALL_CONTACT to { call -> callContact(call) },
        MachineTools.OPEN_APP to { call -> openApp(call) },
        MachineTools.READ_SCREEN to { _ -> readScreen() },
        MachineTools.TAP_TEXT to { call -> tapText(call) },
        MachineTools.SCROLL to { call -> scroll(call) },
        MachineTools.NAVIGATE to { call -> navigate(call) },
        MachineTools.READ_NOTIFICATIONS to { _ -> readNotifications() },
        MachineTools.ANSWER to { call -> ToolResult.ok(call.string("text") ?: "") },
        MachineTools.UNSUPPORTED to { call ->
            ToolResult.failed("I cannot do that yet.", call.string("reason"))
        },
    )

    /** Tools declared to the model but not wired up here would fail silently at runtime. */
    val unimplemented: List<String>
        get() = MachineTools.all.map { it.name }.filterNot { handlers.containsKey(it) }

    suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.Main) {
        Log.i(TAG, "tool ${call.tool} ${call.arguments}")
        val handler = handlers[call.tool]
            ?: return@withContext ToolResult.failed("I do not know how to do that.")
        handler(call)
    }

    // ---- Clock ------------------------------------------------------------------

    /**
     * Delegates to whatever Clock app the phone has, rather than running an alarm
     * engine of our own. An alarm the user cannot find and cancel in the app they
     * already know is a liability, not a feature.
     */
    private fun setAlarm(call: ToolCall): ToolResult {
        val hour = TimeResolver.to24Hour(call.int("hour"), call.string("meridiem"))
            ?: return ToolResult.failed("I did not catch the time.")
        val minute = TimeResolver.minuteOf(call.int("minute"))
            ?: return ToolResult.failed("That is not a time I can set.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            // Stay in the overlay rather than throwing the user into the Clock app.
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            call.string("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        return launch(intent, "Alarm set for ${clockTime(hour, minute)}.", "No clock app could set that.")
    }

    private fun setTimer(call: ToolCall): ToolResult {
        val seconds = TimeResolver.totalSeconds(
            call.int("hours"),
            call.int("minutes"),
            call.int("seconds"),
        ) ?: return ToolResult.failed("I did not catch how long for.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            call.string("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        return launch(intent, "Timer set for ${spokenDuration(seconds)}.", "No clock app could set that.")
    }

    private fun showAlarms(): ToolResult =
        launch(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Here are your alarms.", "No clock app to open.")

    // ---- Reminders ---------------------------------------------------------------

    private suspend fun createReminder(call: ToolCall): ToolResult {
        val task = call.string("task") ?: return ToolResult.failed("What should I remind you about?")
        val hour = TimeResolver.to24Hour(call.int("hour"), call.string("meridiem"))
        val minute = TimeResolver.minuteOf(call.int("minute")) ?: 0
        return reminders.create(task, hour, minute, call.bool("tomorrow"))
    }

    // ---- Messaging ---------------------------------------------------------------

    private fun sendMessage(call: ToolCall): ToolResult {
        val recipient = call.string("recipient") ?: return ToolResult.failed("Who should I message?")
        val body = call.string("body") ?: return ToolResult.failed("What should I say?")
        val number = contacts.resolveNumber(recipient)
            ?: return ToolResult.failed("I could not find $recipient.")

        // Composes rather than sends. Silently sending a message a speech recogniser
        // produced is the one action here with no undo, so the user sees it first.
        val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", body)
        }
        return launch(
            intent,
            "I have written that to $recipient. Send it when you are ready.",
            "No messaging app is available.",
        )
    }

    private fun callContact(call: ToolCall): ToolResult {
        val recipient = call.string("recipient") ?: return ToolResult.failed("Who should I call?")
        val number = contacts.resolveNumber(recipient)
            ?: return ToolResult.failed("I could not find $recipient.")
        val intent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))
        return launch(intent, "Calling $recipient.", "No dialler is available.")
    }

    // ---- Apps --------------------------------------------------------------------

    private fun openApp(call: ToolCall): ToolResult {
        val name = call.string("app") ?: return ToolResult.failed("Which app?")
        val match = AppLookup(context).find(name)
            ?: return ToolResult.failed("I could not find an app called $name.")
        val intent = context.packageManager.getLaunchIntentForPackage(match.packageName)
            ?: return ToolResult.failed("$name cannot be opened.")
        return launch(intent, "Opening ${match.label}.", "$name could not be opened.")
    }

    // ---- Screen ------------------------------------------------------------------

    private fun readScreen(): ToolResult {
        val service = MachineAccessibilityService.connected()
            ?: return accessibilityMissing()
        val lines = service.readScreenText()
        if (lines.isEmpty()) return ToolResult.failed("There is nothing readable on screen.")
        return ToolResult.ok(lines.take(SPOKEN_LINES).joinToString(". "), lines.joinToString("\n"))
    }

    private suspend fun tapText(call: ToolCall): ToolResult {
        val label = call.string("label") ?: return ToolResult.failed("Tap what?")
        val service = MachineAccessibilityService.connected() ?: return accessibilityMissing()
        return if (service.clickNodeWithText(label)) {
            ToolResult.ok("Tapped $label.")
        } else {
            ToolResult.failed("I could not find $label on screen.")
        }
    }

    private suspend fun scroll(call: ToolCall): ToolResult {
        val service = MachineAccessibilityService.connected() ?: return accessibilityMissing()
        val metrics = context.resources.displayMetrics
        val midX = metrics.widthPixels / 2f
        val down = call.string("direction") != "up"
        // Swiping up moves content down, so the gesture is the inverse of the word.
        val from = if (down) metrics.heightPixels * SCROLL_FAR else metrics.heightPixels * SCROLL_NEAR
        val to = if (down) metrics.heightPixels * SCROLL_NEAR else metrics.heightPixels * SCROLL_FAR
        return if (service.swipe(midX, from, midX, to)) {
            ToolResult.ok("Scrolled.")
        } else {
            ToolResult.failed("I could not scroll that.")
        }
    }

    private fun navigate(call: ToolCall): ToolResult {
        val service = MachineAccessibilityService.connected() ?: return accessibilityMissing()
        val target = call.string("target") ?: return ToolResult.failed("Where to?")
        val done = when (target) {
            "back" -> service.pressBack()
            "home" -> service.pressHome()
            "recents" -> service.openRecents()
            "notifications" -> service.openNotificationShade()
            "quick_settings" -> service.openQuickSettings()
            else -> false
        }
        return if (done) ToolResult.ok("Done.") else ToolResult.failed("I could not do that.")
    }

    private fun readNotifications(): ToolResult {
        val listener = MachineNotificationListener.connected()
            ?: return ToolResult.failed(
                "I do not have notification access yet.",
                "Grant Notification access under System access.",
            )
        val items = listener.snapshot().filterNot { it.ongoing }
        if (items.isEmpty()) return ToolResult.ok("Nothing is waiting.")
        val spoken = items.take(SPOKEN_NOTIFICATIONS).joinToString(". ") {
            "${it.title}: ${it.text}".trim().trimEnd(':')
        }
        return ToolResult.ok(spoken, items.joinToString("\n") { "${it.packageName}  ${it.title}  ${it.text}" })
    }

    private fun accessibilityMissing() = ToolResult.failed(
        "I cannot touch the screen yet.",
        "Turn on the accessibility service under System access.",
    )

    // ---- Helpers ------------------------------------------------------------------

    private fun launch(intent: Intent, spoken: String, failure: String): ToolResult = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ToolResult.ok(spoken)
    } catch (e: android.content.ActivityNotFoundException) {
        Log.w(TAG, "no activity for ${intent.action}", e)
        ToolResult.failed(failure)
    } catch (e: SecurityException) {
        Log.w(TAG, "not permitted: ${intent.action}", e)
        ToolResult.failed(failure)
    }

    private fun clockTime(hour: Int, minute: Int): String {
        val suffix = if (hour < NOON) "am" else "pm"
        val twelve = when {
            hour % NOON == 0 -> NOON
            else -> hour % NOON
        }
        return if (minute == 0) "$twelve $suffix" else "$twelve:${minute.toString().padStart(2, '0')} $suffix"
    }

    private fun spokenDuration(seconds: Int): String = when {
        seconds % SECONDS_PER_HOUR == 0 -> plural(seconds / SECONDS_PER_HOUR, "hour")

        seconds >= SECONDS_PER_MINUTE && seconds % SECONDS_PER_MINUTE == 0 ->
            plural(seconds / SECONDS_PER_MINUTE, "minute")

        else -> plural(seconds, "second")
    }

    private fun plural(count: Int, unit: String) = if (count == 1) "one $unit" else "$count ${unit}s"

    companion object {
        private const val TAG = "TheMachine"
        private const val NOON = 12
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 3600
        private const val SPOKEN_LINES = 12
        private const val SPOKEN_NOTIFICATIONS = 5

        /** Gesture endpoints as a fraction of screen height. */
        private const val SCROLL_FAR = 0.75f
        private const val SCROLL_NEAR = 0.30f

        /** Declared so the permission is visible next to the code that needs it. */
        const val SMS_PERMISSION = Manifest.permission.READ_SMS
    }
}
