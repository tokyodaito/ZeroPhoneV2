package com.numenlabs.zerophonev2.core.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionPolicyTest {
    @Test
    fun `three consecutive good frames confirm presence - two do not`() {
        val p = AttentionPolicy(presentAfterFrames = 3, lostAfterMillis = 2_000)
        assertNull(p.onFrame(0, true))
        assertNull(p.onFrame(200, true))
        assertFalse(p.isPresent)
        assertEquals(AttentionPolicy.Event.Present, p.onFrame(400, true))
        assertTrue(p.isPresent)
    }

    @Test
    fun `absence beyond window declares loss`() {
        val p = AttentionPolicy(presentAfterFrames = 1, lostAfterMillis = 2_000)
        p.onFrame(0, true)
        assertTrue(p.isPresent)
        assertNull(p.onFrame(1_000, false)) // still within window
        assertTrue(p.isPresent)
        assertEquals(AttentionPolicy.Event.Lost, p.onFrame(2_001, false)) // 2001ms since last good frame
        assertFalse(p.isPresent)
        // Re-presence works after a loss.
        assertEquals(AttentionPolicy.Event.Present, p.onFrame(2_500, true))
    }

    @Test
    fun `blink shorter than window does not lose presence`() {
        val p = AttentionPolicy(presentAfterFrames = 1, lostAfterMillis = 2_000)
        p.onFrame(0, true)
        p.onFrame(300, false)
        p.onFrame(600, false)
        assertNull(p.onFrame(900, true))
        assertTrue(p.isPresent)
    }

    @Test
    fun `reset clears state`() {
        val p = AttentionPolicy(presentAfterFrames = 1, lostAfterMillis = 2_000)
        p.onFrame(0, true)
        p.reset()
        assertFalse(p.isPresent)
        assertEquals(AttentionPolicy.Event.Present, p.onFrame(100, true))
    }
}
