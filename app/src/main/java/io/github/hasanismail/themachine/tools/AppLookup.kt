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

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** An installed app the user could plausibly have meant. */
data class InstalledApp(val label: String, val packageName: String)

/**
 * Resolves a spoken app name to an installed package.
 *
 * Speech recognition mangles app names constantly — "Spotify" comes back as "spot a
 * fee" — so matching is deliberately forgiving: exact, then prefix, then contains,
 * then a squashed comparison that ignores spacing and punctuation entirely.
 */
class AppLookup(private val context: Context) {

    private val launchable: List<InstalledApp> by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager
            .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map {
                InstalledApp(
                    label = it.loadLabel(context.packageManager).toString(),
                    packageName = it.activityInfo.packageName,
                )
            }
            .distinctBy { it.packageName }
    }

    fun find(spoken: String): InstalledApp? {
        val needle = spoken.trim()
        if (needle.isEmpty()) return null
        val squashedNeedle = squash(needle)

        return launchable.firstOrNull { it.label.equals(needle, ignoreCase = true) }
            ?: launchable.firstOrNull { it.label.startsWith(needle, ignoreCase = true) }
            ?: launchable.firstOrNull { it.label.contains(needle, ignoreCase = true) }
            ?: launchable.firstOrNull { squash(it.label) == squashedNeedle }
            ?: launchable.firstOrNull { squash(it.label).contains(squashedNeedle) }
    }

    /** Lowercase letters and digits only, so spacing and punctuation cannot matter. */
    private fun squash(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
}
