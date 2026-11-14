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

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.ijazshare.assistant.settings.AssistantSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * Hands a [HomeCommand] to the Home Assistant app on the phone and waits for the owner's server to answer.
 *
 * Assistant itself never goes online for this. The HA app's share screen fires a `mobile_app.share`
 * event on the owner's server over the connection that app already keeps, at home or away. The owner's
 * automation there checks the key, and the labels and allowed actions of the entities named, acts or
 * reads, and answers with the HA app's `command_broadcast_intent`, which arrives here as [ACTION_REPLY].
 */
class HomeLink(private val context: Context) {

    private val settings = AssistantSettings(context)

    suspend fun run(call: ToolCall): ToolResult {
        val key = settings.homeKeyNow()
        return when (val command = HomeCommand.of(call)) {
            is HomeCommand.Decline -> ToolResult.failed(command.reason)
            is HomeCommand.Act -> if (key == null) help() else send(command, key)
            null -> help()
        }
    }

    private suspend fun help(): ToolResult {
        val trigger = settings.homeTriggerNow()
        return ToolResult.failed(
            "I can switch things, set the thermostat, lock, close, or tell you how things are. " +
                "Say: $trigger, turn off the kitchen light. Or: $trigger, is the garage door open?",
        )
    }

    private suspend fun send(command: HomeCommand.Act, key: String): ToolResult {
        // Never all digits or a number with an exponent: Home Assistant would read it back as a number.
        val id = "i" + randomHex(ID_BYTES)
        val reply = CompletableDeferred<Map<String, String>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Any app can send this action. Only the id just sent counts, and only its first reply.
                if (intent.getStringExtra("id") == id) {
                    reply.complete(REPLY_EXTRAS.associateWith { intent.getStringExtra(it).orEmpty() })
                }
            }
        }
        // Registered before the share: at home the answer is back in a fraction of a second.
        context.registerReceiver(receiver, IntentFilter(ACTION_REPLY), Context.RECEIVER_EXPORTED)
        return try {
            if (share(command.json(id), key)) {
                command.said(withTimeoutOrNull(REPLY_MILLIS) { reply.await() })
            } else {
                ToolResult.failed("The Home Assistant app is not installed.")
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    /**
     * An explicit intent, so the key reaches the HA app's share screen and nothing else. In a task of its
     * own: joining the HA app's task would leave the HA app open on top once the share screen closes.
     */
    private fun share(text: String, key: String): Boolean = HA_PACKAGES.any { pkg ->
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .setClassName(pkg, SHARE_ACTIVITY)
            .putExtra(Intent.EXTRA_SUBJECT, key)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
        try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "no Home Assistant share screen in $pkg", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "not permitted to share to $pkg", e)
            false
        }
    }

    companion object {
        /** What the owner's automation sends back: the command's `id`, a `status`, and for a question the state. */
        const val ACTION_REPLY = "io.github.ijazshare.assistant.HA_REPLY"

        /** A new random key, for the owner to paste into their automation. */
        fun newKey(): String = randomHex(KEY_BYTES)

        private fun randomHex(bytes: Int) =
            ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

        private val REPLY_EXTRAS =
            listOf("status", "state", "unit", "temperature", "target", "assumed", "low", "high", "on", "blind")

        // The Play build, then the F-Droid one; both keep the share screen under the same class name.
        private val HA_PACKAGES =
            listOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal")
        private const val SHARE_ACTIVITY = "io.homeassistant.companion.android.share.ShareActivity"

        // Home Assistant re-sends an unconfirmed local reply through its push relay after 10 s, so allow for that.
        private const val REPLY_MILLIS = 15_000L
        private const val ID_BYTES = 8
        private const val KEY_BYTES = 16
        private const val TAG = "HomeLink"
    }
}
