package com.numenlabs.zerophonev2.data

import kotlinx.serialization.Serializable

/**
 * Per-app switches for distracting apps, configured once in the picker and
 * frozen after the setup lock (they are part of the committed self-control
 * contract).
 */
@Serializable
data class PerAppConfig(
    /**
     * No 5-minute window: one unlock per session. The session lasts until the
     * app leaves the foreground (minimized / switched away) — then the app is
     * re-suspended and the 60-second gate applies again.
     */
    val perSession: Boolean = false,
)
