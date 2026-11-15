package com.robertlangat.todoplanner.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.robertlangat.todoplanner.container
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val r = applicationContext.container.sync.run(SyncManager.Mode.SYNC)) {
        is SyncManager.Result.Success -> Result.success()
        SyncManager.Result.NeedsSignIn -> Result.success() // shown in settings; retrying won't help
        is SyncManager.Result.Failure -> if (r.retryable && runAttemptCount < 5) Result.retry() else Result.failure()
    }

    companion object {
        private const val PERIODIC = "drive-sync-periodic"
        private const val SOON = "drive-sync-soon"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(network).build(),
            )
        }

        /** Debounced sync shortly after local edits. */
        fun scheduleSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                SOON, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(30, TimeUnit.SECONDS).setConstraints(network).build(),
            )
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(SOON)
        }
    }
}
