package com.numenlabs.zerophonev2.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.numenlabs.zerophonev2.gate.GateActivity
import kotlinx.coroutines.runBlocking

/**
 * Fired by the AlarmManager when the 5-minute access window ends. Reconciles
 * (re-suspends the package — Android itself closes it if it is foreground)
 * and, if the grant expired exactly now, re-shows the 60-second gate. The
 * Device Owner uid is exempt from background-activity-launch restrictions,
 * so [GateActivity] can be started straight from here (V1 ReLockAlarmReceiver
 * structure).
 */
class GrantAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_GRANT_EXPIRED) return
        val pendingResult = goAsync()
        Thread {
            try {
                val engine = (context.applicationContext as com.numenlabs.zerophonev2.ZeroPhoneApp).container.engine
                // Media hold: mid-video the deadline rolls forward instead of closing
                // the window. Re-check here so a race between the watcher and the
                // alarm cannot cut a video off.
                val extended = runBlocking { engine.extendGrantWhileMedia() }
                if (!extended) {
                    // DNS facet skipped: its blocking probe must not eat the goAsync window.
                    val result = runBlocking { engine.reconcile(includeDns = false) }
                    EnforcementService.ensureStarted(context)
                    if (result is EnforcementEngine.ReconcileResult.GrantExpired) {
                        // BAL denial does not throw — if we are not (or no longer) the device
                        // owner the direct start would be silently dropped, so notify instead.
                        if (engine.isDeviceOwner()) {
                            GateActivity.start(context, result.packageName)
                        } else {
                            GateActivity.notifyGateBlocked(context, result.packageName)
                        }
                    }
                }
            } catch (_: Exception) {
                // Never crash the alarm pipeline; catch-up on next resume/boot covers it.
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_GRANT_EXPIRED = "com.numenlabs.zerophonev2.action.GRANT_EXPIRED"
    }
}
