package com.numenlabs.zerophonev2

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.numenlabs.zerophonev2.enforcement.EnforcementService
import com.numenlabs.zerophonev2.enforcement.WatchdogWorker
import kotlinx.coroutines.launch

/**
 * App shell: DI container, clock-change listeners (runtime-registered —
 * TIME_SET/TIMEZONE_CHANGED are implicit broadcasts), watchdog scheduling
 * and an initial reconcile on every process start (self-healing).
 */
class ZeroPhoneApp : Application() {
    lateinit var container: AppContainer
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var clockReconcileRunnable: Runnable? = null

    private val clockReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                scheduleClockReconcile()
            }
        }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            clockReceiver,
            IntentFilter(Intent.ACTION_TIME_CHANGED).also {
                it.addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        WatchdogWorker.enqueue(this)
        container.engine.scope.launch {
            try {
                container.engine.reconcile()
            } catch (_: Exception) {
            }
            EnforcementService.ensureStarted(this@ZeroPhoneApp)
        }
    }

    /** 500 ms debounce — clock edits arrive as bursts of broadcasts (V1). */
    private fun scheduleClockReconcile() {
        clockReconcileRunnable?.let { handler.removeCallbacks(it) }
        clockReconcileRunnable =
            Runnable {
                clockReconcileRunnable = null
                container.engine.scope.launch {
                    try {
                        container.engine.onClockChanged()
                    } catch (_: Exception) {
                    }
                }
            }.also { handler.postDelayed(it, 500L) }
    }
}
