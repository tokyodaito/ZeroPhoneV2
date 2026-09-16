package com.numenlabs.zerophonev2.enforcement

import android.app.admin.DevicePolicyManager
import android.os.UserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enforced Private DNS (DNS-over-TLS) via the device-owner API.
 *
 * [applySpecifiedHost] is BLOCKING on the framework side (a 5-second TLS
 * probe of the resolver) — always call it from a coroutine; this controller
 * hops to Dispatchers.IO itself.
 */
class DnsController(
    private val devicePolicyManager: DevicePolicyManager,
    private val adminComponent: android.content.ComponentName,
) {
    sealed interface DnsResult {
        data object Ok : DnsResult

        data object HostNotServing : DnsResult

        data class Failure(val message: String) : DnsResult
    }

    suspend fun applySpecifiedHost(host: String): DnsResult =
        withContext(Dispatchers.IO) {
            try {
                when (devicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost(adminComponent, host)) {
                    DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR -> DnsResult.Ok
                    DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING -> DnsResult.HostNotServing
                    else -> DnsResult.Failure("PRIVATE_DNS_SET_ERROR_FAILURE_SETTING")
                }
            } catch (e: IllegalArgumentException) {
                DnsResult.Failure(e.message ?: "Некорректное имя хоста")
            } catch (e: Exception) {
                DnsResult.Failure(e.message ?: "Не удалось применить")
            }
        }

    /** Greys out Settings → Private DNS ("managed by admin"). */
    fun applyRestriction() {
        try {
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        } catch (_: Exception) {
        }
    }

    fun clearRestriction() {
        try {
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        } catch (_: Exception) {
        }
    }

    /** Back to the factory-default "Automatic" (opportunistic) mode. */
    fun undo() {
        try {
            devicePolicyManager.setGlobalPrivateDnsModeOpportunistic(adminComponent)
        } catch (_: Exception) {
        }
        clearRestriction()
    }
}
