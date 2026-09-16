package com.numenlabs.zerophonev2.data

import com.numenlabs.zerophonev2.core.ledger.ActiveGrant
import kotlinx.serialization.Serializable

/**
 * Single persisted state of the app (atomic updates via one DataStore key,
 * serialized with kotlinx.serialization; decode failures degrade to defaults).
 */
@Serializable
data class AppState(
    val distractingPackages: Set<String> = emptySet(),
    /** Per-app switches for distracting apps (per-session unlock). */
    val perAppConfig: Map<String, PerAppConfig> = emptyMap(),
    /**
     * Apps where grayscale is OFF while they are in the foreground (e.g. Anki:
     * productive, not gated, but better in color). Independent of the
     * distracting list; frozen after the setup lock.
     */
    val colorPackages: Set<String> = emptySet(),
    val activeGrant: ActiveGrant? = null,
    /** Packages we actually suspended ourselves (reality, not intent). */
    val lastSuspended: Set<String> = emptySet(),
    val grayscaleEnforced: Boolean = true,
    /** User's pre-enforcement daltonizer values, captured once for restore. */
    val grayscaleOriginalEnabled: Int? = null,
    val grayscaleOriginalMode: Int? = null,
    /**
     * One-shot latch for the capture above — NULL values are legitimate
     * originals (fresh device has no daltonizer keys), so null-ness alone
     * must not re-trigger capture (it would snapshot our OWN enforced state).
     */
    val grayscaleOriginalsCaptured: Boolean = false,
    val dnsHost: String = "",
    val dnsEnforced: Boolean = false,
    val dnsAppliedOk: Boolean = false,
    val dnsLastError: String = "",
    /**
     * Set by «Сохранить и заблокировать настройки»: after this, NOTHING can be
     * turned off from the phone — grayscale/DNS/deactivation UI disappears and
     * the only off-switch is [com.numenlabs.zerophonev2.enforcement.AdbDeactivateReceiver]
     * (adb-only).
     */
    val locked: Boolean = false,
    /** Don't fire the 5-minute timer while the granted app is playing media (video). */
    val pauseTimerOnMedia: Boolean = true,
    /** Progressive gate: day (ISO) the per-app entry counters belong to. */
    val gateUsageDate: String = "",
    val gateUsageCounts: Map<String, Int> = emptyMap(),
    val grantDurationMillis: Long = 300_000L,
    val gateDurationMillis: Long = 60_000L,
    val clockSkewMillis: Long = 0L,
    val clockAnchorRealMillis: Long? = null,
    val clockAnchorElapsedMillis: Long? = null,
    val gatePassCount: Int = 0,
    val gateAbandonCount: Int = 0,
    val uninstallBlocked: Boolean = false,
)
