package com.numenlabs.zerophonev2.enforcement

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** One-shot snapshot of the provisioning/permission state for the UI. */
data class ProvisioningStatus(
    val isDeviceOwner: Boolean,
    val hasWriteSecureSettings: Boolean,
    val canScheduleExactAlarms: Boolean,
    val hasCameraPermission: Boolean,
    val hasNotificationPermission: Boolean,
    val hasUsageAccess: Boolean,
    val isEnforcementServiceRunning: Boolean,
) {
    val grayscalePossible: Boolean get() = hasWriteSecureSettings
}

object ProvisioningStatusChecker {
    fun check(context: Context): ProvisioningStatus {
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        val isDeviceOwner =
            try {
                dpm.isDeviceOwnerApp(context.packageName)
            } catch (_: Exception) {
                false
            }
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val canScheduleExactAlarms =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager?.canScheduleExactAlarms() ?: false
            } else {
                true
            }
        return ProvisioningStatus(
            isDeviceOwner = isDeviceOwner,
            hasWriteSecureSettings =
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED,
            canScheduleExactAlarms = canScheduleExactAlarms,
            hasCameraPermission =
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            hasNotificationPermission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                } else {
                    true
                },
            hasUsageAccess = ForegroundWatcher.hasUsageAccess(context),
            isEnforcementServiceRunning = EnforcementService.isRunning,
        )
    }
}
