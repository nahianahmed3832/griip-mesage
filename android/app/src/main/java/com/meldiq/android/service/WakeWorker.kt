package com.meldiq.android.service

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.meldiq.android.GriipApp
import com.meldiq.android.R
import com.meldiq.android.di.AppContainer
import com.meldiq.android.notify.Channels
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the app running while a push wake-up's fetch ([com.meldiq.android.data.repo.WakeFetch]) runs: an expedited
 * job, so it starts at once and has network access even while the phone dozes. The fetch itself runs in the profile's
 * container; wake-ups that arrive meanwhile extend it, so one job covers them (KEEP).
 */
class WakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as GriipApp).container ?: return Result.success()
        // Normally the wake-up already started the fetch. WorkManager also re-runs this job after the process died
        // mid-fetch, and then nothing has: fetch again (an extra round costs nothing once the backlog is in).
        if (!c.wakeFetch.busy.value) c.wakeFetch.request()
        withTimeoutOrNull(MAX_RUN_MS) { c.wakeFetch.busy.first { !it } }
        c.wakeFetch.lastOutcome.value?.let { Log.i(TAG, "wake-up fetch: $it") }
        return Result.success()
    }

    /** Android 11 and older run expedited work as a foreground service, which needs a (quiet) notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Channels.CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_meldiq)
            .setContentTitle(applicationContext.getString(R.string.push_fetching))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "WakeWorker"
        private const val NAME = "meldiq-wake"
        private const val NOTIFICATION_ID = 4104
        /** Longest one wake-up keeps the app alive: the fetch's own limits, plus a margin. */
        private const val MAX_RUN_MS = 3 * 60_000L

        /** A wake-up for [c]'s profile (push message, boot): fetch now or once more, and keep the app alive until done. */
        fun wake(context: Context, c: AppContainer) {
            c.wakeFetch.request()
            val req = OneTimeWorkRequestBuilder<WakeWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
        }
    }
}
