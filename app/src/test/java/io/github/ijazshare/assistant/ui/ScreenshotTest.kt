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

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ijazshare.assistant.assistant.CommandCache
import io.github.ijazshare.assistant.history.QueryLog
import io.github.ijazshare.assistant.history.QueryRecord
import io.github.ijazshare.assistant.history.QuerySource
import io.github.ijazshare.assistant.history.Resolution
import io.github.ijazshare.assistant.models.ModelRegistry
import io.github.ijazshare.assistant.models.ModelRole
import io.github.ijazshare.assistant.models.ModelStorage
import io.github.ijazshare.assistant.settings.AssistantSettings
import io.github.ijazshare.assistant.tools.AssistantTools
import io.github.ijazshare.assistant.tools.ToolCall
import io.github.ijazshare.assistant.ui.context.ContextScreen
import io.github.ijazshare.assistant.ui.history.HistoryScreen
import io.github.ijazshare.assistant.ui.models.ModelManagerScreen
import io.github.ijazshare.assistant.ui.onboarding.AccessChecklist
import io.github.ijazshare.assistant.ui.theme.AssistantTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.RandomAccessFile

/**
 * Draws the Assistant screens with fictional data. Without `-Pscreens.dir` nothing is written and
 * the tests only prove each screen composes; see the record command in design/screens/assistant.
 *
 * ponytail: the screens are drawn by their own composables inside [ScreenFrame] rather than
 * through the Activities, which Robolectric would need a manifest entry for to add nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    // The compose rule launches a plain ComponentActivity, which a unit test has no manifest
    // entry for (ui-test-manifest would put it in the debug APK), so register it first.
    private val registerActivity = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
                base.evaluate()
            }
        }
    }
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(registerActivity).around(compose)

    private val dir = System.getProperty("assistant.screens.dir")
        ?: File("build/outputs/screens").path

    @Before
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { AssistantTheme { content() } }
        compose.waitForIdle()
        // DataStore and file reads finish on another dispatcher.
        Thread.sleep(SETTLE_MILLIS)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }

    private fun framed(title: String, body: @Composable (Modifier) -> Unit): @Composable () -> Unit =
        { ScreenFrame(title = title, onBack = {}, content = body) }

    private fun home(state: HomeState) = shoot(
        if (state.accessMissing) "home-access-missing" else "home-all-granted",
    ) { HomeContent(state = state, onOpen = {}) }

    @Test
    fun homeAllGranted() = home(HomeState(12, 12, 3, 3, "Sam", NOW))

    @Test
    fun homeAccessMissing() = home(HomeState(9, 12, 2, 3, "Sam", NOW))

    @Test
    fun history() {
        val log = QueryLog(app)
        log.append(
            record(
                0,
                "set a timer for ten minutes",
                AssistantTools.SET_TIMER,
                "Timer set for 10 minutes.",
                Resolution.CACHE,
            ),
        )
        log.append(
            record(1, "what is the capital of France", AssistantTools.ANSWER, "Paris.", Resolution.MODEL),
        )
        log.append(
            record(
                2,
                "text Alex running late",
                AssistantTools.SEND_MESSAGE,
                "I could not find Alex.",
                Resolution.MODEL,
                ok = false,
            ),
        )
        log.append(record(3, "mumble", null, "Sorry, I did not catch that.", Resolution.FAILED, ok = false))
        val cache = CommandCache.shared(File(app.getExternalFilesDir(null), CommandCache.FILE_NAME))
        cache.learn("set a timer for 10 minutes", ToolCall(AssistantTools.SET_TIMER, mapOf("minutes" to "10")))
        cache.learn("open Maps", ToolCall(AssistantTools.OPEN_APP, mapOf("app" to "Maps")))
        shoot("history", framed("History") { HistoryScreen(it) })
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xhdpi")
    fun models() {
        val registry = ModelRegistry(app)
        val storage = ModelStorage(app)
        // Sparse files of the right length make a model look installed without a gigabyte on disk.
        registry.byRole(ModelRole.STT).first { it.isDefault }.let { asset ->
            RandomAccessFile(storage.target(asset), "rw").use { it.setLength(asset.byteSize) }
        }
        shoot("models", framed("Models") { ModelManagerScreen(it) })
    }

    @Test
    @Config(qualifiers = "w411dp-h2800dp-xhdpi")
    fun context() {
        runBlocking {
            val settings = AssistantSettings(app)
            settings.setAdminName("Sam")
            settings.setOwnNumber("+15550100")
        }
        shoot("context", framed("Context") { ContextScreen(it) })
    }

    @Test
    @Config(qualifiers = "w411dp-h1800dp-xhdpi")
    fun accessChecklist() {
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        shoot("access", framed("System access") { AccessChecklist(it.padding16()) })
    }

    private fun Modifier.padding16(): Modifier =
        this.then(Modifier.padding(horizontal = 16.dp, vertical = 20.dp))

    // ponytail: a test fixture builder; a parameter object would only restate QueryRecord.
    @Suppress("LongParameterList")
    private fun record(
        minutesAgo: Int,
        said: String,
        tool: String?,
        spoken: String,
        how: Resolution,
        ok: Boolean = true,
    ) = QueryRecord(
        atEpochMillis = NOW - minutesAgo * MINUTE,
        transcript = said,
        source = QuerySource.VOICE,
        resolution = how,
        tool = tool,
        spoken = spoken,
        success = ok,
        llmMillis = if (how == Resolution.MODEL) MODEL_MILLIS - minutesAgo * 100 else 0,
    )

    private companion object {
        const val SETTLE_MILLIS = 600L
        const val MODEL_MILLIS = 1_600L
        const val MINUTE = 60_000L
        const val NOW = 1_793_000_000_000L
    }
}
