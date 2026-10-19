package com.meldiq.android.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.meldiq.android.GriipApp
import java.util.concurrent.TimeUnit

/**
 * Automatic encrypted backups (docs/BACKUP.md): every few days, while the battery isn't low, the active account's
 * backup is written to the file the person chose (for example in Google Drive, through Android's file picker). An
 * account that isn't active on this phone is backed up the next time it is. The outcome shows in Data & Storage.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as GriipApp).container ?: return Result.retry()
        val profile = inputData.getString(KEY_PROFILE) ?: return Result.failure()
        if (container.profile.id != profile) return Result.success()
        container.backups.runAuto() // records success or the reason it failed
        return Result.success()
    }

    companion object {
        private const val KEY_PROFILE = "profile"
        private fun name(profile: String) = "meldiq-backup-$profile"

        fun schedule(context: Context, profile: String, days: Int) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(days.toLong().coerceAtLeast(1), TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .setInputData(workDataOf(KEY_PROFILE to profile))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(name(profile), ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(context: Context, profile: String) {
            WorkManager.getInstance(context).cancelUniqueWork(name(profile))
        }
    }
}
