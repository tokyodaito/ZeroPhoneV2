package com.numenlabs.zerophonev2.enforcement

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Foreground-app detection via UsageEvents (needs the PACKAGE_USAGE_STATS
 * special access — granted either with one adb command or from the app's
 * button that deep-links into Settings). Powers the per-session unlock and
 * the per-app color switches.
 */
object ForegroundWatcher {
    fun hasUsageAccess(context: Context): Boolean =
        try {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode =
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }

    /** The package of the most recently resumed activity over the last window. */
    fun lastForegroundPackage(context: Context): String? =
        try {
            val usageStatsManager = context.getSystemService(UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            val events = usageStatsManager.queryEvents(now - WINDOW_MILLIS, now)
            var pkg: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    pkg = event.packageName
                }
            }
            pkg
        } catch (_: Exception) {
            // SecurityException without usage access, or a flaky stats service.
            null
        }

    private const val WINDOW_MILLIS = 4_000L
}
