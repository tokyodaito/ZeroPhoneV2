package com.numenlabs.zerophonev2.enforcement

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.numenlabs.zerophonev2.admin.ZeroDeviceAdminReceiver

/**
 * Thin DPM wrapper for package suspension (V1 PolicyApplier patterns).
 */
class SuspensionController(
    private val context: Context,
) {
    private val devicePolicyManager: DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    val adminComponent: ComponentName = ComponentName(context, ZeroDeviceAdminReceiver::class.java)

    /**
     * Applies suspension in one batch, retrying failed packages individually.
     * Packages that refuse suspension are skipped (treated as protected) —
     * never fatal. Returns the packages actually applied, so the caller's
     * bookkeeping only records reality: a partially applied set differs from
     * the target and is retried on the next reconcile.
     */
    fun setPackagesSuspendedSafely(
        packages: Set<String>,
        suspended: Boolean,
    ): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val failed: List<String> =
            try {
                val failedNames =
                    devicePolicyManager.setPackagesSuspended(
                        adminComponent,
                        packages.toTypedArray(),
                        suspended,
                    )
                if (failedNames == null) emptyList() else packages.filter { it in failedNames }
            } catch (_: Exception) {
                packages.toList()
            }
        val applied = (packages - failed.toSet()).toMutableSet()
        for (packageName in failed) {
            try {
                devicePolicyManager.setPackagesSuspended(
                    adminComponent,
                    arrayOf(packageName),
                    suspended,
                )
                applied.add(packageName)
            } catch (_: Exception) {
                // Package refuses suspension — skip it (protected).
            }
        }
        return applied
    }

    /** Extra runtime-protected packages: active IME and the current default home launcher. */
    fun dynamicProtectedPackages(): Set<String> {
        val protected = mutableSetOf<String>()
        try {
            Settings.Secure
                .getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
                ?.let { protected.add(it) }
        } catch (_: Exception) {
        }
        try {
            @Suppress("DEPRECATION")
            context.packageManager
                .resolveActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
                )?.activityInfo
                ?.packageName
                ?.takeIf { it != context.packageName }
                ?.let { protected.add(it) }
        } catch (_: Exception) {
        }
        return protected
    }
}
