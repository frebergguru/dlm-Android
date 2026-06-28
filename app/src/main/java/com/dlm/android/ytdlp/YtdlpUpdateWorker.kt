package com.dlm.android.ytdlp

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dlm.android.repo.AppContainer
import java.util.concurrent.TimeUnit

/**
 * Checks for a newer yt-dlp at least once a week, even if the app isn't opened,
 * so video downloads keep working as sites change. Runs only when a network is
 * available; WorkManager handles Doze/retry.
 */
class YtdlpUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ok = runCatching {
            AppContainer.get(applicationContext).ytdlp.checkForUpdate()
        }.getOrDefault(false)
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "ytdlp-weekly-update"

        /** Schedule the weekly check (idempotent — keeps any existing schedule). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<YtdlpUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
