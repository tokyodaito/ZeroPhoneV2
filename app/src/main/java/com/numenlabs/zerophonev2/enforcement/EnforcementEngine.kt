package com.numenlabs.zerophonev2.enforcement

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import com.numenlabs.zerophonev2.admin.ZeroDeviceAdminReceiver
import com.numenlabs.zerophonev2.core.clock.ClockAnchor
import com.numenlabs.zerophonev2.core.clock.ClockSkewPolicy
import com.numenlabs.zerophonev2.core.ledger.ActiveGrant
import com.numenlabs.zerophonev2.core.ledger.GrantLedger
import com.numenlabs.zerophonev2.core.policy.GrayscalePolicy
import com.numenlabs.zerophonev2.core.policy.SuspendSetComputer
import com.numenlabs.zerophonev2.data.AppState
import com.numenlabs.zerophonev2.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Central orchestrator (V1 PolicyApplier role): one mutex-serialized,
 * idempotent [reconcile] that re-applies every enforcement facet to match the
 * persisted state, plus the grant lifecycle. All mutations go through here.
 */
class EnforcementEngine(
    private val appContext: Context,
    private val repository: SettingsRepository,
    private val suspension: SuspensionController,
    private val grayscale: GrayscaleController,
    private val dns: DnsController,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mutex = Mutex()

    private val devicePolicyManager: DevicePolicyManager =
        appContext.getSystemService(DevicePolicyManager::class.java)

    val adminComponent: ComponentName = ComponentName(appContext, ZeroDeviceAdminReceiver::class.java)

    fun isDeviceOwner(): Boolean =
        try {
            devicePolicyManager.isDeviceOwnerApp(appContext.packageName)
        } catch (_: Exception) {
            false
        }

    sealed interface ReconcileResult {
        data object NotDeviceOwner : ReconcileResult

        data object NoGrantActive : ReconcileResult

        data class GrantActive(
            val packageName: String,
            val remainingMillis: Long,
        ) : ReconcileResult

        /** A grant expired and was closed during THIS pass (the alarm may re-show the gate). */
        data class GrantExpired(val packageName: String) : ReconcileResult
    }

    /**
     * Entry point for every trigger: resume, boot, alarm, package events,
     * watchdog, settings edits.
     *
     * @param includeDns the DNS facet does a blocking ~5 s TLS probe; receivers
     * running inside goAsync windows must pass false and let the WorkManager
     * watchdog / app resume retry the DNS apply.
     */
    suspend fun reconcile(includeDns: Boolean = true): ReconcileResult =
        mutex.withLock {
            reconcileInternal(includeDns)
        }

    /** Fast path: re-assert grayscale only (ContentObserver callback / FGS self-check). */
    fun requestGrayscaleReassert() {
        scope.launch {
            try {
                applyGrayscaleTarget(repository.snapshot())
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Current foreground package, maintained by the EnforcementService watcher
     * loop; consulted by the grayscale target so a per-app "color" switch is
     * respected everywhere (incl. observer re-asserts — no fighting).
     */
    @Volatile
    var foregroundPackage: String? = null
        private set

    /**
     * Foreground confirmed by 2+ consecutive polls. The grayscale hysteresis
     * uses it: entering a color app switches instantly, but LEAVING color
     * requires a stable foreign foreground — otherwise every transient
     * system dialog/transition flash flickers the screen gray and back.
     */
    @Volatile
    var stableForegroundPackage: String? = null
        private set

    /** Called by the watcher on every foreground CHANGE (color switches — no debounce needed). */
    fun onForegroundChanged(pkg: String?) {
        foregroundPackage = pkg
        scope.launch {
            try {
                applyGrayscaleTarget(repository.snapshot(), pkg)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Called by the watcher only for a STABLE foreground (same package on 2+
     * consecutive polls). Activity transitions briefly flash the launcher;
     * only a genuinely settled foreign app may end a per-session window. A
     * short grace period after the window opened protects the launch itself.
     */
    fun onForegroundStable(pkg: String?) {
        stableForegroundPackage = pkg
        scope.launch {
            try {
                // A settled non-color foreground is what finally turns grayscale back on.
                applyGrayscaleTarget(repository.snapshot(), pkg)
                val state = repository.snapshot()
                val grant = state.activeGrant ?: return@launch
                if (!grant.perSession) return@launch
                if (pkg == null || pkg == grant.packageName) return@launch
                if (effectiveNowMillis(state) - grant.openedAtMillis < GRACE_AFTER_OPEN_MILLIS) return@launch
                mutex.withLock {
                    val fresh = repository.snapshot()
                    val active = fresh.activeGrant
                    if (active != null && active.perSession && pkg != active.packageName &&
                        effectiveNowMillis(fresh) - active.openedAtMillis >= GRACE_AFTER_OPEN_MILLIS
                    ) {
                        suspension.setPackagesSuspendedSafely(setOf(active.packageName), suspended = true)
                        repository.update {
                            it.copy(
                                activeGrant = null,
                                lastSuspended = it.lastSuspended + active.packageName,
                            )
                        }
                        GrantAlarmScheduler.cancel(appContext)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Grayscale write with the per-app color exception + hysteresis: color
     * applies while the LATEST foreground is a color app (instant on), and
     * stays applied until the STABLE foreground is a non-color app (no
     * flicker on transition flashes). Writes only when the pair differs.
     */
    private fun applyGrayscaleTarget(
        state: AppState,
        pkg: String? = foregroundPackage,
    ) {
        // Grayscale globally off (pre-lock toggle / deactivated) — nothing to enforce.
        if (!state.grayscaleEnforced) return
        if (!grayscale.hasWritePermission()) return
        val colorApp =
            (pkg != null && pkg in state.colorPackages) ||
                (stableForegroundPackage != null && stableForegroundPackage in state.colorPackages)
        val target =
            if (colorApp) {
                GrayscalePolicy.Values(0, GrayscalePolicy.MODE_DISABLED)
            } else {
                GrayscalePolicy.ENFORCED
            }
        val current = grayscale.readCurrent()
        if (current.first != target.daltonizerEnabled || current.second != target.daltonizerMode) {
            grayscale.apply(target)
        }
    }

    /** Called after the 60-second gate completes: open the 5-minute window. */
    suspend fun openGrant(packageName: String): ActiveGrant? =
        mutex.withLock {
            val state = repository.snapshot()
            if (!isDeviceOwner()) return null
            // Never strand a previous window's package: re-suspend it before overwriting the grant.
            state.activeGrant?.let { existing ->
                if (existing.packageName != packageName) {
                    suspension.setPackagesSuspendedSafely(setOf(existing.packageName), suspended = true)
                    repository.update { it.copy(lastSuspended = it.lastSuspended + existing.packageName) }
                }
            }
            val unsuspended = suspension.setPackagesSuspendedSafely(setOf(packageName), suspended = false)
            if (packageName !in unsuspended) {
                // Platform refuses to unsuspend (protected package) — do not open a dead window.
                return null
            }
            val grant =
                GrantLedger.open(
                    packageName = packageName,
                    nowEffectiveMillis = effectiveNowMillis(state),
                    durationMillis = state.grantDurationMillis,
                    id = UUID.randomUUID().toString(),
                    perSession =
                        state.perAppConfig[packageName]?.perSession == true ||
                            packageName in state.protectedPackages,
                )
            // Progressive gate: count this successful entry (per-app, daily).
            val today = java.time.LocalDate.now().toString()
            repository.update { current ->
                val rolledDate = current.gateUsageDate != today
                val baseCounts = if (rolledDate) emptyMap() else current.gateUsageCounts
                current.copy(
                    activeGrant = grant,
                    lastSuspended = current.lastSuspended - packageName,
                    gatePassCount = current.gatePassCount + 1,
                    gateUsageDate = today,
                    gateUsageCounts = baseCounts + (packageName to (baseCounts[packageName] ?: 0) + 1),
                )
            }
            if (!grant.perSession) {
                GrantAlarmScheduler.schedule(appContext, realMillisFor(grant.deadlineMillis, state.clockSkewMillis))
            }
            grant
        }

    /** Early close of the window (settings UI). */
    suspend fun revokeActiveGrant() {
        mutex.withLock {
            repository.update { it.copy(activeGrant = null) }
            reconcileInternal(includeDns = false)
        }
    }

    /**
     * Media hold: called by the watcher / alarm receiver when the granted app
     * is actively playing media near its deadline. Rolls the deadline forward
     * in 60-second steps so the timer never fires mid-video; returns true when
     * an extension was actually applied (the alarm receiver then skips the
     * close-out).
     */
    suspend fun extendGrantWhileMedia(): Boolean =
        mutex.withLock {
            val state = repository.snapshot()
            val grant = state.activeGrant ?: return false
            val playing = com.numenlabs.zerophonev2.media.MediaWatcher.isPlaying(appContext, grant.packageName)
            if (!GrantLedger.shouldExtendWhileMedia(
                    grant,
                    effectiveNowMillis(state),
                    mediaPlaying = playing,
                    pauseOnMediaEnabled = state.pauseTimerOnMedia,
                )
            ) {
                return false
            }
            val extended = GrantLedger.extendWhileMedia(grant)
            repository.update { it.copy(activeGrant = extended) }
            if (!extended.perSession) {
                GrantAlarmScheduler.schedule(appContext, realMillisFor(extended.deadlineMillis, state.clockSkewMillis))
            }
            true
        }

    fun recordAbandon() {
        scope.launch {
            try {
                repository.update { it.copy(gateAbandonCount = it.gateAbandonCount + 1) }
            } catch (_: Exception) {
            }
        }
    }

    /** Wall-clock edit detected (TIME_SET / TIMEZONE_CHANGED): rebase the effective timeline. */
    suspend fun onClockChanged() {
        mutex.withLock {
            val state = repository.snapshot()
            val anchorReal = state.clockAnchorRealMillis
            val anchorElapsed = state.clockAnchorElapsedMillis
            val newSkew =
                if (anchorReal != null && anchorElapsed != null) {
                    ClockSkewPolicy.skewAfterClockChange(
                        anchor = ClockAnchor(anchorReal, anchorElapsed),
                        skewMillis = state.clockSkewMillis,
                        realWallclockMillis = System.currentTimeMillis(),
                        elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                    )
                } else {
                    0L
                }
            repository.update { it.copy(clockSkewMillis = newSkew) }
            reconcileInternal(includeDns = false)
        }
    }

    /**
     * Full deactivation — order is critical: every device-owner-requiring
     * call must precede [DevicePolicyManager.clearDeviceOwnerApp].
     */
    suspend fun deactivate() {
        mutex.withLock {
            val state = repository.snapshot()
            // 1. Stop enforcement surfaces.
            GrantAlarmScheduler.cancel(appContext)
            // 2. Unsuspend everything we might have suspended.
            val fullRelease =
                SuspendSetComputer.computeFullReleaseSet(
                    lastSuspended = state.lastSuspended,
                    distractingPackages = state.distractingPackages + state.protectedPackages,
                    activeGrantPackage = state.activeGrant?.packageName,
                )
            suspension.setPackagesSuspendedSafely(fullRelease, suspended = false)
            // 3. Grayscale off, restoring captured originals.
            if (grayscale.hasWritePermission()) {
                grayscale.apply(
                    GrayscalePolicy.restoreValues(state.grayscaleOriginalEnabled, state.grayscaleOriginalMode),
                )
            }
            if (isDeviceOwner()) {
                // 4. DNS back to opportunistic + unlock the Settings UI.
                dns.undo()
                // 5. Allow uninstall.
                try {
                    devicePolicyManager.setUninstallBlocked(adminComponent, appContext.packageName, false)
                } catch (_: Exception) {
                }
                // 7. Drop device ownership — LAST device-owner call.
                try {
                    devicePolicyManager.clearDeviceOwnerApp(appContext.packageName)
                } catch (_: Exception) {
                }
            }
            // 8. Reset persisted state. grayscaleEnforced MUST be false — the
            // FGS/observers would otherwise re-impose grayscale after deactivation.
            repository.update { AppState(grayscaleEnforced = false) }
        }
    }

    // ------------------------------------------------------------------ //

    private suspend fun reconcileInternal(includeDns: Boolean): ReconcileResult {
        if (!isDeviceOwner()) {
            val state = repository.snapshot()
            if (state.activeGrant != null || state.lastSuspended.isNotEmpty()) {
                // Best-effort release — the calls throw without device ownership,
                // the system normally reverts admin suspensions on admin removal.
                try {
                    val toRelease = state.lastSuspended + setNotNull(state.activeGrant?.packageName)
                    suspension.setPackagesSuspendedSafely(toRelease, suspended = false)
                } catch (_: Exception) {
                }
                repository.update { it.copy(activeGrant = null, lastSuspended = emptySet()) }
            }
            GrantAlarmScheduler.cancel(appContext)
            return ReconcileResult.NotDeviceOwner
        }

        var state = repository.snapshot()

        // First run under device ownership: protect ourselves + brand the admin dialog.
        if (!state.uninstallBlocked) {
            try {
                devicePolicyManager.setUninstallBlocked(adminComponent, appContext.packageName, true)
                devicePolicyManager.setShortSupportMessage(adminComponent, SUPPORT_MESSAGE)
            } catch (_: Exception) {
            }
            repository.update { it.copy(uninstallBlocked = true) }
            state = state.copy(uninstallBlocked = true)
        }

        // Prune the expired grant.
        var expiredPackage: String? = null
        val nowEffective = effectiveNowMillis(state)
        if (state.activeGrant != null && !GrantLedger.isActive(state.activeGrant, nowEffective)) {
            expiredPackage = state.activeGrant!!.packageName
            repository.update { it.copy(activeGrant = null) }
            state = state.copy(activeGrant = null)
        }

        // Suspension facet.
        val activeGrantPackage = state.activeGrant?.packageName
        var target =
            SuspendSetComputer.computeSuspendSet(
                distractingPackages = state.distractingPackages + state.protectedPackages,
                selfPackage = appContext.packageName,
                protectedPackages =
                    SuspendSetComputer.DEFAULT_PROTECTED_PACKAGES + suspension.dynamicProtectedPackages(),
            )
        if (activeGrantPackage != null) target -= activeGrantPackage
        if (target != state.lastSuspended) {
            val release = SuspendSetComputer.computeReleaseSet(state.lastSuspended, target)
            val releasedOk = suspension.setPackagesSuspendedSafely(release, suspended = false)
            val appliedOk = suspension.setPackagesSuspendedSafely(target, suspended = true)
            // Record reality: applied suspensions + releases that stuck (stuck ones retry next pass).
            val newLastSuspended = appliedOk + (release - releasedOk)
            repository.update { it.copy(lastSuspended = newLastSuspended) }
            state = state.copy(lastSuspended = newLastSuspended)
        }

        // Grayscale facet.
        if (state.grayscaleEnforced && grayscale.hasWritePermission()) {
            if (!state.grayscaleOriginalsCaptured) {
                // Capture originals once, BEFORE the first enforcement write.
                val current = grayscale.readCurrent()
                repository.update {
                    it.copy(
                        grayscaleOriginalEnabled = current.first,
                        grayscaleOriginalMode = current.second,
                        grayscaleOriginalsCaptured = true,
                    )
                }
            }
            applyGrayscaleTarget(state)
        }

        // DNS facet (retries pending applies; blocking call runs on IO inside the controller).
        if (includeDns && state.dnsEnforced && state.dnsHost.isNotBlank() && !state.dnsAppliedOk) {
            val trimmedHost = state.dnsHost.trim()
            when (val result = dns.applySpecifiedHost(trimmedHost)) {
                DnsController.DnsResult.Ok -> {
                    dns.applyRestriction()
                    repository.update { it.copy(dnsAppliedOk = true, dnsLastError = "") }
                    state = state.copy(dnsAppliedOk = true, dnsLastError = "")
                }

                is DnsController.DnsResult.HostNotServing -> {
                    val message = "Хост не отвечает DNS-over-TLS (порт 853)"
                    repository.update { it.copy(dnsLastError = message) }
                    state = state.copy(dnsLastError = message)
                }

                is DnsController.DnsResult.Failure -> {
                    repository.update { it.copy(dnsLastError = result.message) }
                    state = state.copy(dnsLastError = result.message)
                }
            }
        }

        // Alarm facet (per-session grants have no deadline — the watcher closes them).
        state.activeGrant?.let { grant ->
            if (grant.perSession) {
                GrantAlarmScheduler.cancel(appContext)
            } else {
                GrantAlarmScheduler.schedule(appContext, realMillisFor(grant.deadlineMillis, state.clockSkewMillis))
            }
        } ?: GrantAlarmScheduler.cancel(appContext)

        refreshClockAnchor()

        return when {
            expiredPackage != null -> ReconcileResult.GrantExpired(expiredPackage)
            state.activeGrant != null ->
                ReconcileResult.GrantActive(
                    packageName = state.activeGrant!!.packageName,
                    remainingMillis =
                        GrantLedger.remainingMillis(state.activeGrant, effectiveNowMillis(state)) ?: 0L,
                )

            else -> ReconcileResult.NoGrantActive
        }
    }

    /** Effective now = real wall clock + accumulated anti-tamper skew. */
    private fun effectiveNowMillis(state: AppState): Long = System.currentTimeMillis() + state.clockSkewMillis

    /** Converts an effective-time deadline to real wall-clock time for RTC_WAKEUP alarms. */
    private fun realMillisFor(effectiveMillis: Long, skewMillis: Long): Long = effectiveMillis - skewMillis

    /** Re-bases the tamper anchor onto the current real clock (V1). */
    private suspend fun refreshClockAnchor() {
        repository.update {
            it.copy(
                clockAnchorRealMillis = System.currentTimeMillis(),
                clockAnchorElapsedMillis = SystemClock.elapsedRealtime(),
            )
        }
    }

    private companion object {
        const val SUPPORT_MESSAGE = "ZeroPhone: откройте приложение ZeroPhone, чтобы войти"

        /** A fresh window must not be closed by launch-transition foreground flicker. */
        const val GRACE_AFTER_OPEN_MILLIS = 5_000L
    }
}

private fun setNotNull(value: String?): Set<String> = if (value == null) emptySet() else setOf(value)
