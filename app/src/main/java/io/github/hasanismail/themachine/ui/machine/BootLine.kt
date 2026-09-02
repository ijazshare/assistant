/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.ui.machine

import androidx.compose.ui.graphics.Color
import io.github.hasanismail.themachine.ui.theme.MachineColors

/** One line of the cold-start readout rendered by [BootSequence]. */
data class BootLine(val label: String, val value: String, val tone: Color = MachineColors.Asset)
