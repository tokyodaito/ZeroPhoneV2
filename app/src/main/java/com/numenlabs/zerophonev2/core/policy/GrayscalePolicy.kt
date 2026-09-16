package com.numenlabs.zerophonev2.core.policy

/**
 * Pure decisions for the forced-grayscale enforcement.
 *
 * Grayscale = simulated monochromacy via the two hidden secure settings
 * consumed by ColorDisplayService (same mechanism as Bedtime mode):
 *   accessibility_display_daltonizer_enabled = 1
 *   accessibility_display_daltonizer         = 0  (DALTONIZER_SIMULATE_MONOCHROMACY)
 */
object GrayscalePolicy {
    const val KEY_DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
    const val KEY_DALTONIZER_MODE = "accessibility_display_daltonizer"
    const val KEY_DALTONIZER_SATURATION = "accessibility_display_saturation_level"

    const val MODE_DISABLED = -1
    const val MODE_MONOCHROMACY = 0

    data class Values(
        val daltonizerEnabled: Int,
        val daltonizerMode: Int,
    )

    val ENFORCED = Values(daltonizerEnabled = 1, daltonizerMode = MODE_MONOCHROMACY)

    /**
     * Value pair to write when enforcing, or null when the current values
     * already match (dirty-check — prevents ContentObserver echo loops and
     * avoids pointless writes).
     *
     * Absent keys (null) are the NORMAL fresh-device state — nothing seeds
     * the daltonizer rows until the user first touches color correction —
     * so (null, null) must still enforce, not read as "unreadable".
     */
    fun shouldWrite(currentEnabled: Int?, currentMode: Int?): Values? {
        if (currentEnabled == ENFORCED.daltonizerEnabled && currentMode == ENFORCED.daltonizerMode) return null
        return ENFORCED
    }

    /** Is the pair read from settings already the enforced grayscale state? */
    fun isEnforced(currentEnabled: Int?, currentMode: Int?): Boolean =
        currentEnabled == ENFORCED.daltonizerEnabled && currentMode == ENFORCED.daltonizerMode

    /**
     * Values to restore the user's pre-enforcement state. Unknown originals
     * degrade to a plain "daltonizer disabled" — never leave grayscale on.
     * Captured values that EQUAL the enforced pair are treated as poisoned
     * (snapshot of our own state, taken by an older build) and also restore
     * to "off" — turning the phone monochrome forever is the worse failure.
     */
    fun restoreValues(originalEnabled: Int?, originalMode: Int?): Values {
        if (originalEnabled == ENFORCED.daltonizerEnabled && originalMode == ENFORCED.daltonizerMode) {
            return Values(daltonizerEnabled = 0, daltonizerMode = MODE_DISABLED)
        }
        return Values(
            daltonizerEnabled = originalEnabled ?: 0,
            daltonizerMode = originalMode ?: MODE_DISABLED,
        )
    }
}
