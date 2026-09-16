package com.numenlabs.zerophonev2.core.clock

/**
 * Pure policy behind the wall-clock tamper guard (port of ZeroPhone V1).
 *
 * The engine keeps a monotonic view of "now" (real clock + [skewMillis]) so
 * that moving the device clock backwards cannot extend a 5-minute access
 * window and jumping it forward cannot mint extra time.
 *
 * An anchor (real wall clock + monotonic elapsed reading taken at the same
 * moment) lets the policy tell real passage of time from a clock edit: with
 * no tampering, `realNow ≈ anchorReal + (elapsedNow - anchorElapsed)`. A
 * deviation beyond the thresholds is a manual date/time change, and the skew
 * is adjusted so the effective clock continues from where it truly was.
 *
 * Timezone changes are deliberately NOT skewed (legitimate travel): they do
 * not move the wall-clock epoch.
 */
object ClockSkewPolicy {
    /** Backward edits smaller than this are treated as NTP corrections. */
    const val BACKWARD_TOLERANCE_MILLIS: Long = 2L * 60_000L

    /** Forward edits larger than this are treated as a manual date change. */
    const val FORWARD_TOLERANCE_MILLIS: Long = 60L * 60_000L

    /**
     * Skew to apply after a possible clock change.
     *
     * @param anchor last persisted anchor; a stale anchor whose elapsed
     * reading exceeds [elapsedRealtimeMillis] means a reboot happened after
     * it was written — the real clock is then trusted as-is (skew reset).
     * @return the new absolute skew: effective now = real now + skew.
     */
    fun skewAfterClockChange(
        anchor: ClockAnchor,
        skewMillis: Long,
        realWallclockMillis: Long,
        elapsedRealtimeMillis: Long,
    ): Long {
        if (elapsedRealtimeMillis < anchor.elapsedRealtimeMillis) return 0L
        val expectedReal = anchor.realWallclockMillis + (elapsedRealtimeMillis - anchor.elapsedRealtimeMillis)
        val jump = realWallclockMillis - expectedReal
        if (jump >= -BACKWARD_TOLERANCE_MILLIS && jump <= FORWARD_TOLERANCE_MILLIS) {
            return skewMillis // Normal passage or a small NTP-style correction.
        }
        // The anchor is periodically re-based onto the CURRENT real clock
        // (see EnforcementEngine.refreshClockAnchor), so expectedReal
        // extrapolates the possibly-already-tampered timeline. Continuity
        // therefore needs a RELATIVE adjustment: keep the accumulated skew
        // and undo only this jump. A back-then-forward edit pair cancels
        // out (skew 0), repeated same-direction edits accumulate — the
        // effective timeline never rewinds and mints no extra time.
        return skewMillis - jump
    }
}

/** Real wall clock + monotonic elapsed reading captured at one shared moment. */
data class ClockAnchor(
    val realWallclockMillis: Long,
    val elapsedRealtimeMillis: Long,
)
