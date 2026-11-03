/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.ui

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ijazshare.assistant.ui.theme.AssistantColors
import io.github.ijazshare.assistant.ui.theme.AssistantTheme

/**
 * An edge-to-edge secondary page: the shared top bar (back arrow and [title]) over one
 * screen composable. System back and the arrow both just finish the Activity.
 */
abstract class ScreenActivity : ComponentActivity() {
    protected abstract val title: String
    protected abstract val content: @Composable (Modifier) -> Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The slide comes from the theme's window animations (all API levels, off when the
        // transition scale is 0). ponytail: on API 34+ also switch it off when the animator
        // scale is 0; on 33 the theme animation alone applies.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && animatorScale() == 0f) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        setContent {
            AssistantTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = AssistantColors.Void,
                ) { insets ->
                    ScreenFrame(
                        title = title,
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        modifier = Modifier.padding(insets),
                        content = content,
                    )
                }
            }
        }
    }

    private fun animatorScale(): Float =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}
