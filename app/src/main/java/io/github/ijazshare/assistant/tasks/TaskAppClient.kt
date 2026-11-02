/*
 * Assistant — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.assistant.tasks

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.ijazshare.assistant.BuildConfig
import io.github.ijazshare.tasks.ipc.ITaskCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/**
 * The link to the Tasks app (spec 4.1 and 4.2): a bound service behind a signature permission, bound ahead of
 * the command ([prewarm]) and let go 30 s after the last session. One per process, so the undo token
 * outlives a session.
 */
class TaskAppClient private constructor(private val context: Context) {
    private val pkg = if (BuildConfig.DEBUG) "io.github.ijazshare.tasks.debug" else "io.github.ijazshare.tasks"
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val letGo = Runnable { unbind() }

    @Volatile private var service: ITaskCommand? = null
    private var connecting: CompletableDeferred<ITaskCommand?>? = null
    private var bound = false
    private var refused = false

    /** The last command's undo token, kept until it expires; a newer command replaces it (4.8). */
    @Volatile var lastUndo: TaskUndo? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = ITaskCommand.Stub.asInterface(binder)
            connecting?.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            connecting = CompletableDeferred() // the system rebinds; the next send waits for that
        }

        override fun onBindingDied(name: ComponentName) = unbind()

        override fun onNullBinding(name: ComponentName) = unbind()
    }

    fun isInstalled(): Boolean = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** True while an undo token is held and has not expired. */
    fun canUndo(): Boolean = lastUndo?.let { it.expiresEpochMs > System.currentTimeMillis() } == true

    /** Binds now, so the process is up by the time the words are. A no-op while bound. */
    @Synchronized
    fun prewarm() {
        main.removeCallbacks(letGo)
        if (bound || !isInstalled()) return
        refused = false
        bound = try {
            context.bindService(
                Intent().setComponent(ComponentName(pkg, "io.github.ijazshare.tasks.ipc.CommandService")),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "bind refused", e)
            refused = true
            false
        }
        if (bound && connecting == null) connecting = CompletableDeferred()
    }

    /** The voice session ended: unbind after 30 s unless a new one starts. */
    fun sessionEnded() {
        main.removeCallbacks(letGo)
        main.postDelayed(letGo, IDLE_MILLIS)
    }

    @Synchronized
    fun unbind() {
        main.removeCallbacks(letGo)
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
        connecting = null
    }

    /**
     * Sends one request and returns Tasks' answer, or an `error` response with the fixed sentence for what
     * failed here (4.9). One retry with the same request id, only when the binding was lost, never after a timeout.
     */
    @Suppress("ReturnCount") // each early return is one of the fixed failures of spec 4.9
    suspend fun send(request: TaskRequest, version: String = BuildConfig.VERSION_NAME): TaskResponse {
        if (!isInstalled()) return failure("not_installed", "Install Tasks to keep lists and reminders.")
        val text = TaskContract.encode(request, zone = ZoneId.systemDefault().id, version = version)
        repeat(2) {
            val svc = connect() ?: return failure(
                if (refused) "caller_not_trusted" else "timeout",
                if (refused) "The task app is signed with a different key." else TIMED_OUT,
            )
            try {
                val answer = withTimeoutOrNull(TIMEOUT_MILLIS) { scope.async { svc.handle(text) }.await() }
                    ?: return failure("timeout", TIMED_OUT)
                val response = TaskContract.decode(answer) ?: return failure("bad_response", TIMED_OUT)
                if (response.undo != null) {
                    lastUndo = response.undo
                } else if (request.type == "utterance" || request.type == "follow_up") {
                    lastUndo = null
                }
                return response
            } catch (e: DeadObjectException) {
                Log.w(TAG, "task app went away", e)
                unbind()
            }
        }
        return failure("timeout", TIMED_OUT)
    }

    private suspend fun connect(): ITaskCommand? {
        service?.let { return it }
        prewarm()
        val waiting = synchronized(this) { connecting } ?: return null
        return withTimeoutOrNull(TIMEOUT_MILLIS) { waiting.await() }
    }

    private fun failure(code: String, speech: String) = TaskResponse("error", speech, error = TaskError(code))

    companion object {
        private const val TAG = "TaskApp"
        private const val TIMEOUT_MILLIS = 800L
        private const val IDLE_MILLIS = 30_000L
        private const val TIMED_OUT = "The task app didn't answer. Check Tasks before trying again."

        @SuppressLint("StaticFieldLeak") // only ever holds the application context
        @Volatile
        private var instance: TaskAppClient? = null

        fun of(context: Context): TaskAppClient = instance ?: synchronized(this) {
            instance ?: TaskAppClient(context.applicationContext).also { instance = it }
        }
    }
}
