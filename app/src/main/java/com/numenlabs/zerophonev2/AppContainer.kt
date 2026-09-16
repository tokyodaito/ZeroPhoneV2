package com.numenlabs.zerophonev2

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.numenlabs.zerophonev2.admin.ZeroDeviceAdminReceiver
import com.numenlabs.zerophonev2.data.DataStoreSettingsRepository
import com.numenlabs.zerophonev2.enforcement.DnsController
import com.numenlabs.zerophonev2.enforcement.EnforcementEngine
import com.numenlabs.zerophonev2.enforcement.GrayscaleController
import com.numenlabs.zerophonev2.enforcement.SuspensionController

/** Manual DI graph (no framework — single-module app, V2 philosophy). */
class AppContainer(appContext: Context) {
    val repository by lazy { DataStoreSettingsRepository(appContext) }

    val grayscaleController by lazy { GrayscaleController(appContext) }

    val suspensionController by lazy { SuspensionController(appContext) }

    val dnsController by lazy {
        val devicePolicyManager = appContext.getSystemService(DevicePolicyManager::class.java)
        DnsController(
            devicePolicyManager = devicePolicyManager,
            adminComponent = ComponentName(appContext, ZeroDeviceAdminReceiver::class.java),
        )
    }

    val engine by lazy {
        EnforcementEngine(
            appContext = appContext,
            repository = repository,
            suspension = suspensionController,
            grayscale = grayscaleController,
            dns = dnsController,
        )
    }
}
