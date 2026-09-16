package com.numenlabs.zerophonev2.enforcement

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.numenlabs.zerophonev2.core.policy.GrayscalePolicy

/**
 * Reads/writes the two daltonizer secure settings that ColorDisplayService
 * turns into a system-wide grayscale transform, and observes them for
 * re-assertion. Writing requires WRITE_SECURE_SETTINGS (granted once via
 * `adb shell pm grant`) — reads are always allowed.
 */
class GrayscaleController(
    private val context: Context,
) {
    private val handler = Handler(Looper.getMainLooper())

    /** Re-assert debounce — the observer fires on our own writes too. */
    private var pendingReassert: Runnable? = null

    private val observers = mutableListOf<ContentObserver>()

    fun hasWritePermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun readCurrent(): Pair<Int?, Int?> = readKey(GrayscalePolicy.KEY_DALTONIZER_ENABLED) to
        readKey(GrayscalePolicy.KEY_DALTONIZER_MODE)

    private fun readKey(key: String): Int? =
        try {
            Settings.Secure.getInt(context.contentResolver, key)
        } catch (_: Settings.SettingNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }

    fun apply(values: GrayscalePolicy.Values) {
        Settings.Secure.putInt(context.contentResolver, GrayscalePolicy.KEY_DALTONIZER_ENABLED, values.daltonizerEnabled)
        Settings.Secure.putInt(context.contentResolver, GrayscalePolicy.KEY_DALTONIZER_MODE, values.daltonizerMode)
    }

    /**
     * Registers observers on the daltonizer keys (plus the newer saturation
     * key) and calls [onExternalChange] — debounced — whenever any of them
     * changes. The callback must re-check and re-assert idempotently.
     */
    fun registerObservers(onExternalChange: () -> Unit) {
        unregisterObservers()
        val uris =
            listOf(
                Settings.Secure.getUriFor(GrayscalePolicy.KEY_DALTONIZER_ENABLED),
                Settings.Secure.getUriFor(GrayscalePolicy.KEY_DALTONIZER_MODE),
                Settings.Secure.getUriFor(GrayscalePolicy.KEY_DALTONIZER_SATURATION),
            )
        uris.forEach { uri ->
            val observer =
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        pendingReassert?.let { handler.removeCallbacks(it) }
                        pendingReassert =
                            Runnable {
                                pendingReassert = null
                                onExternalChange()
                            }.also { handler.postDelayed(it, 250L) }
                    }
                }
            context.contentResolver.registerContentObserver(uri, false, observer)
            observers.add(observer)
        }
    }

    fun unregisterObservers() {
        observers.forEach { context.contentResolver.unregisterContentObserver(it) }
        observers.clear()
        pendingReassert?.let { handler.removeCallbacks(it) }
        pendingReassert = null
    }
}
