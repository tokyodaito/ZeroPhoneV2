package com.numenlabs.zerophonev2.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.numenlabs.zerophonev2.ZeroPhoneApp
import kotlinx.coroutines.runBlocking

/**
 * Re-arms everything after a reboot: full reconcile (the grayscale setting
 * itself survives reboot — this is defense in depth), the keep-alive service
 * and the watchdog. DataStore lives in credential-encrypted storage, so this
 * receiver is intentionally not directBootAware.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        Thread {
            try {
                val app = context.applicationContext as ZeroPhoneApp
                // DNS facet deferred to the watchdog (blocking probe vs goAsync window).
                runBlocking { app.container.engine.reconcile(includeDns = false) }
                EnforcementService.ensureStarted(context)
                WatchdogWorker.enqueue(context)
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
