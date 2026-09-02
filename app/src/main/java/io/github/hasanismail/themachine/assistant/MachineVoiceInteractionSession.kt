/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.hasanismail.themachine.audio.MachineSounds

/**
 * The overlay that appears when the side button is held.
 *
 * A VoiceInteractionSession is not an Activity, so a ComposeView placed in it has none
 * of the owners Compose needs — no lifecycle, no ViewModelStore, no SavedStateRegistry.
 * This class supplies all three itself; without them the overlay crashes the moment it
 * is inflated.
 */
class MachineVoiceInteractionSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    /**
     * Bumped on every onShow. The content view is created once and reused for the life
     * of the session, so a LaunchedEffect keyed on Unit runs exactly once ever — the
     * greeting animated on the first summon and never again. Keying it on this counter
     * makes each summon a fresh reveal.
     */
    private val showCount = androidx.compose.runtime.mutableIntStateOf(0)

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate()
    }

    override fun onCreateContentView(): View {
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@MachineVoiceInteractionSession)
            setViewTreeViewModelStoreOwner(this@MachineVoiceInteractionSession)
            setViewTreeSavedStateRegistryOwner(this@MachineVoiceInteractionSession)
            setContent {
                AssistantOverlay(
                    showCount = showCount.intValue,
                    onDismiss = { hide() },
                )
            }
        }
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        showCount.intValue += 1
        MachineSounds.play(MachineSounds.Cue.ENGAGE)
        Log.i("TheMachine", "assistant session shown")
    }

    override fun onHide() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onHide()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }
}
