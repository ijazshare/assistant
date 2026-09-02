/*
 * The Machine — an offline voice assistant for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.hasanismail.themachine.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Downloads one asset, in the background, surviving the app being closed.
 *
 * WorkManager is what makes this survive process death: the request is persisted, so a
 * download interrupted by the system being killed resumes on the next opportunity
 * rather than being lost. Combined with HTTP Range resume in [ModelDownloader], a
 * gigabyte download can be interrupted repeatedly and still finish.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val registry = ModelRegistry(context)
    private val storage = ModelStorage(context)
    private val downloader = ModelDownloader(storage)

    override suspend fun doWork(): Result {
        val assetId = inputData.getString(KEY_ASSET_ID) ?: return Result.failure(
            errorData("No asset id supplied"),
        )
        val asset = registry.byId(assetId) ?: return Result.failure(
            errorData("Unknown asset: $assetId"),
        )

        setForeground(foregroundInfo(asset, 0, asset.byteSize))

        val result = downloader.download(asset) { downloaded, total ->
            setProgressAsync(
                Data.Builder()
                    .putString(KEY_ASSET_ID, assetId)
                    .putLong(KEY_DOWNLOADED, downloaded)
                    .putLong(KEY_TOTAL, total)
                    .build(),
            )
        }

        return when (result) {
            is DownloadResult.Success -> Result.success(
                Data.Builder().putString(KEY_ASSET_ID, assetId).build(),
            )

            // Retrying is WorkManager's job — it backs off and waits for connectivity,
            // which is exactly right for a flaky network and wrong for "disk is full".
            is DownloadResult.Failed ->
                if (result.retryable && runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure(errorData(result.reason))
                }

            DownloadResult.Cancelled -> Result.failure(errorData("Cancelled"))
        }
    }

    private fun errorData(message: String) =
        Data.Builder().putString(KEY_ERROR, message).build()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val asset = registry.byId(inputData.getString(KEY_ASSET_ID).orEmpty())
        return foregroundInfo(asset, 0, asset?.byteSize ?: 0)
    }

    private fun foregroundInfo(asset: ModelAsset?, done: Long, total: Long): ForegroundInfo {
        ensureChannel()
        val percent = if (total > 0) ((done * PERCENT) / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading ${asset?.displayName ?: "model"}")
            .setContentText("$percent%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(PERCENT, percent, total <= 0)
            .setSilent(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Progress while The Machine downloads its speech and language models." }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_ASSET_ID = "assetId"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 4201
        private const val MAX_ATTEMPTS = 5
        private const val PERCENT = 100

        private fun workName(assetId: String) = "model-download-$assetId"

        /**
         * Queues a download. KEEP rather than REPLACE: asking twice for the same model
         * should join the download already running, not restart it from zero.
         */
        fun enqueue(context: Context, assetId: String) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(Data.Builder().putString(KEY_ASSET_ID, assetId).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(TAG_ALL)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(assetId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, assetId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(assetId))
        }

        fun observe(context: Context, assetId: String): Flow<WorkInfo?> =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(workName(assetId))
                .map { it.firstOrNull() }

        const val TAG_ALL = "model-download"
    }
}
