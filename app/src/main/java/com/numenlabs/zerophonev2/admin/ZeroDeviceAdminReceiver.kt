package com.numenlabs.zerophonev2.admin

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin entry point. The class path is part of the frozen adb
 * provisioning contract documented in the README:
 *
 *   adb shell dpm set-device-owner com.numenlabs.zerophonev2/.admin.ZeroDeviceAdminReceiver
 *
 * The "app paused by admin" dialog text is set via
 * DevicePolicyManager.setShortSupportMessage (see EnforcementEngine).
 */
class ZeroDeviceAdminReceiver : DeviceAdminReceiver()
