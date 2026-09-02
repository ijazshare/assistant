/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Deliberately does almost nothing: the voice pipeline's
 * heavy pieces (Whisper, llama, Piper) are loaded per-session and freed on session
 * end, so there is nothing to warm up here.
 */
@HiltAndroidApp
class TheMachineApplication : Application()
