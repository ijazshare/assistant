/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The assistant's hands.
 *
 * Everything here is driven by an explicit command the user just spoke — this service
 * does not watch the screen in the background, does not log what it sees, and sends
 * nothing anywhere. It exists so "open the clock app" and "scroll down" can be carried
 * out, because there is no other API on Android that lets an app do that.
 *
 * The service is only bound while it is enabled in Settings; [instance] is null the rest
 * of the time, which is also how callers find out the capability is unavailable.
 */
// A deliberate facade: one small method per device action the assistant can perform.
// Splitting it would mean handing callers a second object that only works while this
// service is bound, which is worse than a long-but-flat surface.
@Suppress("TooManyFunctions")
class MachineAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TheMachine"

        /** Roughly one frame at 60 Hz — long enough to register as a tap, not a hold. */
        private const val TAP_DURATION_MS = 60L
        private const val SWIPE_DURATION_MS = 300L
        private const val GESTURE_TIMEOUT_MS = 5_000L

        @Volatile
        private var instance: MachineAccessibilityService? = null

        /** The live service, or null when the user has not enabled it. */
        fun connected(): MachineAccessibilityService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Required by the platform, deliberately empty.
     *
     * The service subscribes to no event types (see accessibility_service_config.xml),
     * so nothing arrives here. Actions are pulled on demand instead of being driven by
     * a stream of everything happening on screen, which keeps this from being a
     * general-purpose watcher of the user's activity.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---- Global actions ---------------------------------------------------------

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun openNotificationShade(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    // ---- Gestures ---------------------------------------------------------------

    /** Taps a screen coordinate. Suspends until the gesture completes or times out. */
    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build(),
        )
    }

    /** Drags from one point to another — scrolling, swiping between pages, dismissing. */
    suspend fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = SWIPE_DURATION_MS,
    ): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(),
        )
    }

    /**
     * dispatchGesture is callback-based and can report completion, cancellation, or
     * neither if the service loses its window. The timeout is what stops a caller
     * waiting forever in that last case.
     */
    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        val completion = CompletableDeferred<Boolean>()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    completion.complete(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    completion.complete(false)
                }
            },
            null,
        )
        if (!accepted) return false
        return withTimeoutOrNull(GESTURE_TIMEOUT_MS) { completion.await() } ?: false
    }

    // ---- Reading the screen -----------------------------------------------------

    /**
     * Finds the first clickable node whose text or content description contains [text],
     * case-insensitively, and clicks it. This is how "tap Settings" resolves to an
     * actual view rather than a guessed coordinate.
     */
    fun clickNodeWithText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val match = findNode(root) { node ->
                node.isClickable && node.matchesText(text)
            } ?: findClickableAncestorOfMatch(root, text)
            match?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        } finally {
            root.recycleCompat()
        }
    }

    /**
     * A screenshot of the app in front, or null if the system refuses one.
     *
     * Taken through the accessibility service rather than MediaProjection, which would
     * put a consent dialog in the middle of every "what does this say". Where the system
     * allows it the capture is of the foreground window alone, so the assistant's own
     * overlay does not end up reading itself.
     */
    suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { continuation ->
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                // Copied off the hardware buffer before it is closed: a hardware bitmap
                // has no pixels this process can read, and OCR needs them.
                val bitmap = runCatching {
                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
                buffer.close()
                continuation.resume(bitmap)
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "screenshot refused: $errorCode")
                continuation.resume(null)
            }
        }

        val front = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        } ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && front != null) {
                takeScreenshotOfWindow(front.id, mainExecutor, callback)
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }
        }.onFailure {
            Log.w(TAG, "screenshot threw", it)
            continuation.resume(null)
        }
    }

    /**
     * Every piece of text currently on screen, in traversal order.
     *
     * Returned to the caller and never stored: this is read at the moment a command
     * needs it, used to answer that command, and dropped.
     */
    fun readScreenText(limit: Int = 200): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<String>()
        try {
            collectText(root, out, limit)
        } finally {
            root.recycleCompat()
        }
        return out
    }

    /** The package currently in the foreground, for "what am I looking at". */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    // ---- Node traversal ---------------------------------------------------------

    private fun AccessibilityNodeInfo.matchesText(needle: String): Boolean {
        val haystack = listOfNotNull(text?.toString(), contentDescription?.toString())
        return haystack.any { it.contains(needle, ignoreCase = true) }
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, predicate)?.let { return it }
        }
        return null
    }

    /**
     * Labels are frequently a non-clickable TextView inside a clickable row, so a plain
     * "clickable and matching" search misses most real buttons. This finds the matching
     * text first, then walks up for something that will actually accept a click.
     */
    private fun findClickableAncestorOfMatch(
        root: AccessibilityNodeInfo,
        text: String,
    ): AccessibilityNodeInfo? {
        val labelled = findNode(root) { it.matchesText(text) } ?: return null
        var candidate: AccessibilityNodeInfo? = labelled
        while (candidate != null && !candidate.isClickable) {
            candidate = candidate.parent
        }
        return candidate
    }

    private fun collectText(node: AccessibilityNodeInfo, into: MutableList<String>, limit: Int) {
        if (into.size >= limit) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(into::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() && it !in into }?.let(into::add)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, into, limit)
        }
    }

    /**
     * AccessibilityNodeInfo.recycle() is deprecated and a no-op from API 33, which is
     * this app's floor. Kept as a named call so the intent is visible where nodes are
     * finished with, rather than looking like a leak.
     */
    private fun AccessibilityNodeInfo.recycleCompat() = Unit
}
