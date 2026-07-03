package com.dtn.mesh.scheduler

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Utility to enqueue/cancel the periodic ForwardingWorker.
 * Called from DtnForegroundService or the orchestrator on connect/disconnect.
 */
object WorkManagerSetup {

    /**
     * Enqueue the periodic housekeeping worker.
     * Uses KEEP policy so repeated calls don't restart an existing worker.
     */
    fun enqueueForwardingWorker(context: Context, config: SchedulerConfig) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (config.allowMetered) NetworkType.NOT_REQUIRED
                else NetworkType.UNMETERED
            )
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ForwardingWorker>(
            config.intervalMinutes, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .addTag(ForwardingWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ForwardingWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )
    }

    /** Cancel the periodic forwarding worker. */
    fun cancelForwardingWorker(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ForwardingWorker.WORK_NAME)
    }
}
