package com.numenlabs.zerophonev2.core.gate

/**
 * Pure hysteresis turning a stream of per-frame booleans ("a face with open
 * eyes is visible") into stable Present/Lost events: several consecutive good
 * frames confirm presence, a continuous absence window declares loss. Kills
 * single-frame flicker (blinks, detector noise).
 */
class AttentionPolicy(
    private val presentAfterFrames: Int = 3,
    private val lostAfterMillis: Long = 2_000L,
) {
    enum class Event { Present, Lost }

    var isPresent: Boolean = false
        private set

    private var goodStreak = 0
    private var lastGoodAt = Long.MIN_VALUE

    fun onFrame(nowElapsed: Long, faceWithOpenEyes: Boolean): Event? {
        if (faceWithOpenEyes) {
            goodStreak++
            lastGoodAt = nowElapsed
            if (!isPresent && goodStreak >= presentAfterFrames) {
                isPresent = true
                return Event.Present
            }
        } else {
            goodStreak = 0
        }
        if (isPresent && lastGoodAt != Long.MIN_VALUE && nowElapsed - lastGoodAt > lostAfterMillis) {
            isPresent = false
            goodStreak = 0
            return Event.Lost
        }
        return null
    }

    fun reset() {
        isPresent = false
        goodStreak = 0
        lastGoodAt = Long.MIN_VALUE
    }
}
