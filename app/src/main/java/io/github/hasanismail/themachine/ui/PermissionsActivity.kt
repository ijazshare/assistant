/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import io.github.hasanismail.themachine.ui.machine.scanlines
import io.github.hasanismail.themachine.ui.onboarding.AccessChecklist
import io.github.hasanismail.themachine.ui.theme.MachineColors
import io.github.hasanismail.themachine.ui.theme.TheMachineTheme

/** Everything the assistant asks for, on its own page so the home screen stays clean. */
@AndroidEntryPoint
class PermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TheMachineTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MachineColors.Void,
                ) { insets ->
                    AccessChecklist(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MachineColors.Void)
                            .scanlines()
                            .padding(insets)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                    )
                }
            }
        }
    }
}
