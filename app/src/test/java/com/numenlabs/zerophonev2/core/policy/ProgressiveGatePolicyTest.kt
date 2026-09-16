package com.numenlabs.zerophonev2.core.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressiveGatePolicyTest {
    @Test
    fun `duration grows 60 90 120 150 180 and caps there`() {
        assertEquals(60_000L, ProgressiveGatePolicy.durationMillis(0))
        assertEquals(90_000L, ProgressiveGatePolicy.durationMillis(1))
        assertEquals(120_000L, ProgressiveGatePolicy.durationMillis(2))
        assertEquals(150_000L, ProgressiveGatePolicy.durationMillis(3))
        assertEquals(180_000L, ProgressiveGatePolicy.durationMillis(4))
        assertEquals(180_000L, ProgressiveGatePolicy.durationMillis(9))
        assertEquals(180_000L, ProgressiveGatePolicy.durationMillis(100))
    }

    @Test
    fun `negative counts are clamped to the floor`() {
        assertEquals(60_000L, ProgressiveGatePolicy.durationMillis(-3))
    }

    @Test
    fun `entries are per-app and per-day`() {
        val counts = mapOf("com.instagram.android" to 3, "app.other" to 1)
        assertEquals(
            3,
            ProgressiveGatePolicy.entriesToday("2026-09-16", counts, "com.instagram.android", "2026-09-16"),
        )
        assertEquals(
            0,
            ProgressiveGatePolicy.entriesToday("2026-09-16", counts, "unknown.app", "2026-09-16"),
        )
        // A stale (yesterday's) counter has rolled over.
        assertEquals(
            0,
            ProgressiveGatePolicy.entriesToday("2026-09-15", counts, "com.instagram.android", "2026-09-16"),
        )
    }
}
