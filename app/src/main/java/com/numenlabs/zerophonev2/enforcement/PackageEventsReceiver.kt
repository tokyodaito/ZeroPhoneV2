package com.numenlabs.zerophonev2.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.numenlabs.zerophonev2.ZeroPhoneApp
import kotlinx.coroutines.runBlocking

/** Catch-up reconcile after the app itself is updated/replaced. */
class PackageEventsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        Thread {
            try {
                val app = context.applicationContext as ZeroPhoneApp
                runBlocking { app.container.engine.reconcile(includeDns = false) }
                EnforcementService.ensureStarted(context)
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
