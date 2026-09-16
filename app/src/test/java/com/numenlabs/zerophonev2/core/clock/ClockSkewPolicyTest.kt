package com.numenlabs.zerophonev2.core.clock

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockSkewPolicyTest {
    private val anchor = ClockAnchor(realWallclockMillis = 1_000_000L, elapsedRealtimeMillis = 500_000L)

    @Test
    fun `normal passage keeps skew`() {
        // 10 min of elapsed → real moved 10 min too.
        val skew =
            ClockSkewPolicy.skewAfterClockChange(
                anchor = anchor,
                skewMillis = 7L,
                realWallclockMillis = 1_600_000L,
                elapsedRealtimeMillis = 1_100_000L,
            )
        assertEquals(7L, skew)
    }

    @Test
    fun `small ntp correction backwards is tolerated`() {
        val skew =
            ClockSkewPolicy.skewAfterClockChange(
                anchor = anchor,
                skewMillis = 0L,
                realWallclockMillis = 1_000_000L + 10 * 60_000L - 90_000L, // -90 s vs expected
                elapsedRealtimeMillis = 500_000L + 10 * 60_000L,
            )
        assertEquals(0L, skew)
    }

    @Test
    fun `manual backwards edit is undone so window cannot extend`() {
        // User rewinds the clock 2 h during a grant window.
        val elapsedNow = 500_000L + 30 * 60_000L // 30 min passed
        val realNow = 1_000_000L + 30 * 60_000L - 2 * 60 * 60_000L // rewound 2 h
        val skew = ClockSkewPolicy.skewAfterClockChange(anchor, 0L, realNow, elapsedNow)
        // Effective now stays at ~30 min after anchor real time.
        assertEquals(2 * 60 * 60_000L, skew)
    }

    @Test
    fun `forward edit beyond tolerance is undone too`() {
        val elapsedNow = 500_000L + 60_000L
        val realNow = 1_000_000L + 60_000L + 5 * 60 * 60_000L // jumped 5 h forward
        val skew = ClockSkewPolicy.skewAfterClockChange(anchor, 0L, realNow, elapsedNow)
        assertEquals(-5 * 60 * 60_000L, skew)
    }

    @Test
    fun `reboot resets skew to zero`() {
        val skew =
            ClockSkewPolicy.skewAfterClockChange(
                anchor = anchor,
                skewMillis = 123L,
                realWallclockMillis = 2_000_000L,
                elapsedRealtimeMillis = 100_000L, // elapsed went backwards → reboot
            )
        assertEquals(0L, skew)
    }
}
