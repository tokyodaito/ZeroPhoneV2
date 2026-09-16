package com.numenlabs.zerophonev2.enforcement

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.numenlabs.zerophonev2.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Always-on keep-alive for the grayscale re-assertion: ContentObservers only
 * work while the registering process lives, so while grayscale enforcement is
 * on, this specialUse foreground service holds the observers (the OS
 * auto-restarts a START_STICKY service), plus a 60-second safety-net
 * self-check. Started from: MainActivity (visible transition), boot receiver
 * and the exact-alarm receiver (both FGS-start-exempt); the WorkManager
 * watchdog also tries in try/catch (workers are NOT exempt).
 */
class EnforcementService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as com.numenlabs.zerophonev2.ZeroPhoneApp
        // Guard against resurrection: the service is only meaningful while
        // grayscale enforcement is on and writable — otherwise stop at once.
        serviceScope.launch {
            val state = app.container.repository.snapshot()
            if (!state.grayscaleEnforced || !app.container.grayscaleController.hasWritePermission()) {
                stopSelf()
                return@launch
            }
        }
        isRunning = true
        startInForeground()
        app.container.grayscaleController.registerObservers {
            app.container.engine.requestGrayscaleReassert()
        }
        serviceScope.launch {
            while (isActive) {
                delay(SELF_CHECK_INTERVAL_MILLIS)
                app.container.engine.requestGrayscaleReassert()
            }
        }
        startForegroundWatcher(app)
    }

    /**
     * Polls the foreground app (only while the per-app switches actually need
     * it) and feeds changes to the engine: per-session grant closing and the
     * per-app color exception.
     */
    private fun startForegroundWatcher(app: com.numenlabs.zerophonev2.ZeroPhoneApp) {
        serviceScope.launch {
            var lastSeen: String? = null
            var stableCount = 0
            var announced = false
            while (isActive) {
                val state =
                    try {
                        app.container.repository.snapshot()
                    } catch (_: Exception) {
                        null
                    }
                val grant = state?.activeGrant
                val watcherNeeded =
                    state != null &&
                        (
                            grant?.perSession == true ||
                                state.colorPackages.isNotEmpty() ||
                                state.perAppConfig.values.any { it.perSession }
                        )
                // Media hold: a timed grant nearing its deadline while media plays.
                val mediaHoldNeeded =
                    state != null && grant != null && !grant.perSession && state.pauseTimerOnMedia &&
                        com.numenlabs.zerophonev2.media.MediaWatcher.hasNotificationAccess(this@EnforcementService)
                if (!watcherNeeded && !mediaHoldNeeded) {
                    if (announced) {
                        announced = false
                        // Leaving watch mode: make sure grayscale is back on target.
                        app.container.engine.requestGrayscaleReassert()
                    }
                    delay(WATCHER_IDLE_INTERVAL_MILLIS)
                    continue
                }
                announced = true
                if (watcherNeeded) {
                    val pkg = ForegroundWatcher.lastForegroundPackage(this@EnforcementService)
                    if (pkg != lastSeen) {
                        lastSeen = pkg
                        stableCount = 1
                        app.container.engine.onForegroundChanged(pkg)
                    } else if (pkg != null) {
                        stableCount++
                        // Require 2 consecutive identical reads (~2 s): activity
                        // transitions briefly flash the launcher, and that flicker
                        // must not end a per-session window.
                        if (stableCount == STABLE_POLLS_REQUIRED) {
                            app.container.engine.onForegroundStable(pkg)
                        }
                    }
                }
                if (mediaHoldNeeded) {
                    app.container.engine.extendGrantWhileMedia()
                }
                delay(WATCHER_ACTIVE_INTERVAL_MILLIS)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        val app = applicationContext as com.numenlabs.zerophonev2.ZeroPhoneApp
        app.container.grayscaleController.unregisterObservers()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_enforcement),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply { description = getString(R.string.channel_enforcement_desc) },
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.enforcement_notification_title))
            .setContentText(getString(R.string.enforcement_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "zerophonev2_enforcement"
        private const val NOTIFICATION_ID = 1001
        private const val SELF_CHECK_INTERVAL_MILLIS = 60_000L
        private const val WATCHER_ACTIVE_INTERVAL_MILLIS = 1_000L
        private const val WATCHER_IDLE_INTERVAL_MILLIS = 5_000L
        private const val STABLE_POLLS_REQUIRED = 2

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Safe from any context: swallows FGS-start restrictions on 31+. */
        fun ensureStarted(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, EnforcementService::class.java))
            } catch (_: Exception) {
            }
        }

        fun ensureStopped(context: Context) {
            try {
                context.stopService(Intent(context, EnforcementService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
