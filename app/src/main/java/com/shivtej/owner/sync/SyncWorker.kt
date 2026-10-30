package com.shivtej.owner.sync

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.first
import com.shivtej.owner.App
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (App.dbRefusal != null) return Result.success()   // the database is refused: nothing is opened or changed
        val app = applicationContext as App
        if (app.prefs.ownerCode.isBlank()) return Result.success()
        val ok = app.sync.syncNow()
        runCatching { com.shivtej.owner.ui.PhotoCache.syncAll(applicationContext, app.repo.products.first()) }
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints()).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun enqueueNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints()).build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
