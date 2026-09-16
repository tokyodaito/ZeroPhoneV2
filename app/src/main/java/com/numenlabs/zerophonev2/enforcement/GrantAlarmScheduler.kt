package com.numenlabs.zerophonev2.enforcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Schedules the end of the 5-minute access window with AlarmManager
 * (RTC_WAKEUP) — V1 ReLockScheduler pattern. Exact alarm when permitted,
 * otherwise inexact fallback; precision is guaranteed by the persisted
 * deadline + catch-up reconcile on every entry point, so a delayed inexact
 * alarm never leaves the window open for long.
 */
object GrantAlarmScheduler {
    private const val REQUEST_CODE = 1001

    fun schedule(
        context: Context,
        deadlineRealtimeMillis: Long,
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = pendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineRealtimeMillis, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineRealtimeMillis, pendingIntent)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, GrantAlarmReceiver::class.java)
                .setAction(GrantAlarmReceiver.ACTION_GRANT_EXPIRED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
