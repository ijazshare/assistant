/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.wake

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import io.github.hasanismail.themachine.R
import io.github.hasanismail.themachine.assistant.MachineVoiceInteractionService
import io.github.hasanismail.themachine.models.ModelArchive
import io.github.hasanismail.themachine.models.ModelRegistry
import io.github.hasanismail.themachine.models.ModelRole
import io.github.hasanismail.themachine.models.ModelState
import io.github.hasanismail.themachine.models.ModelStorage
import io.github.hasanismail.themachine.ui.MainActivity
import kotlin.math.sqrt

/**
 * Listens for "hey root" while the phone is doing something else.
 *
 * A foreground service with its own audio loop is the only way to hold a microphone open
 * in the background on modern Android. The platform's own hotword APIs — the ones that
 * let a DSP listen at no CPU cost — are gated behind a permission whose protection level
 * is "preinstalled", meaning the app would have to be part of the system image. Holding
 * the assistant role does not help.
 *
 * So the cost of this feature is honest and visible: a notification that cannot be
 * dismissed, and a microphone indicator in the status bar for as long as it runs. It is
 * off unless the user turns it on.
 *
 * Nothing heard here is recorded, transcribed or kept. Audio goes into a keyword spotter
 * one chunk at a time and is discarded; only the phrase itself has any effect, and its
 * effect is to open the assistant exactly as the side button does.
 */
class WakeWordService : Service() {

    private val engine by lazy { WakeWordEngine(modelDirectory()) }

    @Volatile
    private var running = false

    /**
     * True while the assistant itself is listening.
     *
     * Its session opens the microphone the moment this one lets go, and a wake word that
     * kept running through the command would be competing for the same device and could
     * hear the reply spoken back.
     */
    @Volatile
    private var paused = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        if (!hasMicrophone()) {
            Log.w(TAG, "wake: no microphone permission")
            stopSelf()
            return START_NOT_STICKY
        }

        // Declared as a microphone service, which is what makes the recording legal in
        // the background and what puts the indicator in the status bar.
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }.onFailure {
            Log.e(TAG, "wake: could not start in the foreground", it)
            stopSelf()
            return START_NOT_STICKY
        }

        live = this
        running = true
        worker = Thread { listen() }.apply {
            isDaemon = true
            start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        live = null
        running = false
        worker?.interrupt()
        worker = null
        engine.release()
        Log.i(TAG, "wake: stopped")
        super.onDestroy()
    }

    /**
     * The listening loop.
     *
     * Frames below the noise floor never reach the spotter; see [audible].
     */
    @SuppressLint("MissingPermission")
    private fun listen() {
        if (!engine.load()) {
            Log.e(TAG, "wake: model would not load")
            stopSelf()
            return
        }
        val record = openMicrophone()
        if (record == null) {
            stopSelf()
            return
        }
        try {
            pump(record)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "wake: recording stopped", e)
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun openMicrophone(): AudioRecord? {
        val minimum = AudioRecord.getMinBufferSize(
            WakeWordEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // VOICE_RECOGNITION rather than MIC: it is the source the platform points at the
        // person speaking, with the processing a recogniser wants rather than the
        // processing a recording wants.
        val record = runCatching {
            @SuppressLint("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WakeWordEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, CHUNK_SAMPLES * Short.SIZE_BYTES * BUFFERS),
            )
        }.getOrNull()

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "wake: microphone unavailable")
            record?.release()
            return null
        }
        return record
    }

    private fun pump(record: AudioRecord) {
        val shorts = ShortArray(CHUNK_SAMPLES)
        val floats = FloatArray(CHUNK_SAMPLES)
        record.startRecording()
        Log.i(TAG, "wake: listening")

        while (running && !Thread.currentThread().isInterrupted) {
            val read = record.read(shorts, 0, shorts.size)
            // Still read while paused, and throw the audio away: stopping the read would
            // let the buffer overrun, and resuming a stale one hears the past.
            if (read > 0 && !paused && audible(shorts, read, floats)) {
                val chunk = if (read == floats.size) floats else floats.copyOf(read)
                if (engine.accept(chunk)) wake()
            }
        }
    }

    /**
     * Converts one buffer and says whether anything was in it.
     *
     * Most of any hour is silence, and a model that only runs when something is audible
     * costs a fraction of one that runs always — the gate is a sum of squares, which is
     * nothing next to a forward pass.
     */
    private fun audible(shorts: ShortArray, read: Int, into: FloatArray): Boolean {
        var sumSquares = 0.0
        for (i in 0 until read) {
            val sample = shorts[i] / Short.MAX_VALUE.toFloat()
            into[i] = sample
            sumSquares += (sample * sample).toDouble()
        }
        return sqrt(sumSquares / read) >= SILENCE_RMS
    }

    /**
     * Opens the assistant, exactly as the side button does.
     *
     * On the main thread, because showing a session is a window operation and this is
     * being called from the thread that reads the microphone. The microphone is released
     * first: the session opens its own, and leaving two of them contending for one device
     * is the sort of thing that works on the bench and fails in a pocket.
     */
    private fun wake() {
        Log.i(TAG, "wake: opening the assistant")
        paused = true
        Handler(Looper.getMainLooper()).post {
            val shown = MachineVoiceInteractionService.showSessionNow()
            if (!shown) {
                Log.w(TAG, "wake: not the current assistant, nothing to show")
                paused = false
            }
        }
    }

    /** Forgets whatever was half-heard before the assistant took over. */
    private fun engineIdle() {
        engine.reset()
    }

    private fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun modelDirectory(): java.io.File {
        val storage = ModelStorage(this)
        val asset = ModelRegistry(this).byRole(ModelRole.WAKE)
            .firstOrNull { storage.quickState(it) == ModelState.Ready }
            ?: return java.io.File(filesDir, "wake-missing")
        ModelArchive.unpack(storage.target(asset), storage.extractedDir(asset))
        return storage.extractedDir(asset)
    }

    private fun notification(): Notification {
        getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while The Machine is listening for its wake word."
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Listening for \"hey root\"")
            .setContentText("Nothing is recorded or sent anywhere.")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "TheMachine"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 4201

        /** 320 ms, which is the chunk the model was built to step through. */
        const val CHUNK_SAMPLES = 5_120
        private const val BUFFERS = 4

        /** Below this a frame is room tone, and the model is not woken for it. */
        private const val SILENCE_RMS = 0.01f

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /**
         * Told when the assistant opens and closes, so the wake word stands down while a
         * command is being given and picks up again afterwards.
         */
        @Volatile
        private var live: WakeWordService? = null

        fun assistantOpened() {
            live?.paused = true
        }

        fun assistantClosed() {
            live?.let {
                it.engineIdle()
                it.paused = false
            }
        }
    }
}
