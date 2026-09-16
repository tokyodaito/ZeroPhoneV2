package com.numenlabs.zerophonev2.core.ledger

import kotlinx.serialization.Serializable

/**
 * A window during which one distracting package is unsuspended. Either the
 * standard 5-minute window, or — for per-session apps — no deadline at all:
 * the grant lives until the app leaves the foreground.
 * All timestamps are in *effective* milliseconds (wall clock + persisted
 * clock skew), so a manual clock edit cannot extend the window.
 */
@Serializable
data class ActiveGrant(
    val id: String,
    val packageName: String,
    val openedAtMillis: Long,
    val deadlineMillis: Long,
    val perSession: Boolean = false,
    /** Set while the media-hold keeps extending the deadline (video playing). */
    val mediaHold: Boolean = false,
)

/** Pure ledger operations over the optional active grant. */
object GrantLedger {
    /** Deadline sentinel for per-session grants (no wall-clock limit). */
    const val NO_DEADLINE: Long = Long.MAX_VALUE

    /** Media hold: how much a rolling extension adds / how close to expiry we extend. */
    const val MEDIA_EXTENSION_MILLIS: Long = 60_000L
    const val MEDIA_EXTENSION_THRESHOLD_MILLIS: Long = 70_000L

    /**
     * The 5-minute timer must not fire mid-video: when the granted app is
     * actively playing media and the deadline is close, extend it in rolling
     * 60-second steps; the window closes at the first check after playback
     * stops. Pure decision — no Android imports.
     */
    fun shouldExtendWhileMedia(
        grant: ActiveGrant?,
        nowEffectiveMillis: Long,
        mediaPlaying: Boolean,
        pauseOnMediaEnabled: Boolean,
    ): Boolean {
        if (grant == null || grant.perSession || !pauseOnMediaEnabled || !mediaPlaying) return false
        val remaining = grant.deadlineMillis - nowEffectiveMillis
        return remaining in 0..MEDIA_EXTENSION_THRESHOLD_MILLIS
    }
    fun open(
        packageName: String,
        nowEffectiveMillis: Long,
        durationMillis: Long,
        id: String,
        perSession: Boolean = false,
    ): ActiveGrant =
        ActiveGrant(
            id = id,
            packageName = packageName,
            openedAtMillis = nowEffectiveMillis,
            deadlineMillis =
                if (perSession) {
                    NO_DEADLINE
                } else {
                    nowEffectiveMillis + durationMillis
                },
            perSession = perSession,
        )

    /** Strictly positive remaining time; a grant at its deadline is expired. */
    fun remainingMillis(grant: ActiveGrant?, nowEffectiveMillis: Long): Long? {
        if (grant == null) return null
        val remaining = grant.deadlineMillis - nowEffectiveMillis
        return if (remaining > 0) remaining else null
    }

    /** A media-hold extension: deadline +60 s, flagged for the dashboard. */
    fun extendWhileMedia(grant: ActiveGrant): ActiveGrant =
        grant.copy(
            deadlineMillis = grant.deadlineMillis + MEDIA_EXTENSION_MILLIS,
            mediaHold = true,
        )

    fun isActive(grant: ActiveGrant?, nowEffectiveMillis: Long): Boolean =
        remainingMillis(grant, nowEffectiveMillis) != null
}
