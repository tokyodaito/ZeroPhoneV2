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
        installBenignCameraTeardownGuard()
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

    /**
     * Samsung OneUI camera2 teardown race (seen on the M33): after GateActivity
     * unbinds the front camera, the camera service may asynchronously fail a
     * post-close stream operation with SecurityException "Attempt to use camera
     * from a different process than original client" on a camera2 internal
     * thread. It escapes every try/catch we own and is fatal for the process —
     * killing the enforcement observers with it. The camera is already closed
     * at that point, so the error is benign: suppress exactly that one and
     * keep running; anything else still crashes normally.
     */
    private fun installBenignCameraTeardownGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            var cause: Throwable? = throwable
            var benignCameraTeardown = false
            while (cause != null) {
                if (cause is SecurityException &&
                    cause.message?.contains("camera", ignoreCase = true) == true
                ) {
                    benignCameraTeardown = true
                    break
                }
                cause = cause.cause
            }
            if (benignCameraTeardown) {
                android.util.Log.e(TAG, "Suppressed benign camera teardown error", throwable)
            } else {
                previous?.uncaughtException(thread, throwable)
            }
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

    private companion object {
        const val TAG = "ZeroPhoneV2"
    }
}
