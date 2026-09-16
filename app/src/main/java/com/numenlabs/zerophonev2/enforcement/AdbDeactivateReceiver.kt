package com.numenlabs.zerophonev2.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.numenlabs.zerophonev2.ZeroPhoneApp
import kotlinx.coroutines.runBlocking

/**
 * The ONLY off-switch once setup is locked: exported, but the manifest guards
 * it with android:permission="android.permission.WRITE_SECURE_SETTINGS" —
 * only adb shell (which always holds it), the system, or the app itself can
 * deliver; a regular third-party app physically cannot.
 *
 *   adb shell am broadcast --include-stopped-packages \
 *     -a com.numenlabs.zerophonev2.action.ADB_DEACTIVATE \
 *     -n com.numenlabs.zerophonev2/.enforcement.AdbDeactivateReceiver
 *
 * Runs the full ordered restore (unsuspend, grayscale originals, DNS
 * opportunistic, restrictions cleared) and drops device ownership.
 */
class AdbDeactivateReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_ADB_DEACTIVATE) return
        val pendingResult = goAsync()
        Thread {
            try {
                val app = context.applicationContext as ZeroPhoneApp
                EnforcementService.ensureStopped(context)
                runBlocking { app.container.engine.deactivate() }
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_ADB_DEACTIVATE = "com.numenlabs.zerophonev2.action.ADB_DEACTIVATE"

        /** The exact command shown in the in-app unlock guide. */
        const val ADB_COMMAND =
            "adb shell am broadcast --include-stopped-packages -a com.numenlabs.zerophonev2.action.ADB_DEACTIVATE -n com.numenlabs.zerophonev2/.enforcement.AdbDeactivateReceiver"
    }
}
