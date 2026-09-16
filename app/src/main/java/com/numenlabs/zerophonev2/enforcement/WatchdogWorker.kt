package com.numenlabs.zerophonev2.enforcement

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.numenlabs.zerophonev2.ZeroPhoneApp
import java.util.concurrent.TimeUnit

/**
 * Periodic (15 min) safety net: full reconcile (suspension, grayscale, DNS
 * retry, alarm catch-up) and a best-effort restart of the keep-alive service
 * (workers are not exempt from FGS-start restrictions — the try/catch is the
 * design, not an afterthought).
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            val app = applicationContext as ZeroPhoneApp
            app.container.engine.reconcile()
            EnforcementService.ensureStarted(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }

    companion object {
        private const val UNIQUE_WORK_NAME = "zerophonev2_watchdog"

        fun enqueue(context: Context) {
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build(),
                )
        }
    }
}
