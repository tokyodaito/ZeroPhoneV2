package com.numenlabs.zerophonev2.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayscalePolicyTest {
    @Test
    fun `enforced values are monochromacy`() {
        assertEquals(1, GrayscalePolicy.ENFORCED.daltonizerEnabled)
        assertEquals(0, GrayscalePolicy.ENFORCED.daltonizerMode)
    }

    @Test
    fun `shouldWrite is null when already enforced - echo loop guard`() {
        assertNull(GrayscalePolicy.shouldWrite(1, 0))
    }

    @Test
    fun `shouldWrite fires when either value differs`() {
        assertEquals(GrayscalePolicy.ENFORCED, GrayscalePolicy.shouldWrite(0, -1))
        assertEquals(GrayscalePolicy.ENFORCED, GrayscalePolicy.shouldWrite(1, 12))
        assertEquals(GrayscalePolicy.ENFORCED, GrayscalePolicy.shouldWrite(0, 0))
    }

    @Test
    fun `shouldWrite enforces on absent keys - fresh device normal state`() {
        assertEquals(GrayscalePolicy.ENFORCED, GrayscalePolicy.shouldWrite(null, null))
        assertEquals(GrayscalePolicy.ENFORCED, GrayscalePolicy.shouldWrite(0, null))
    }

    @Test
    fun `isEnforced checks`() {
        assertTrue(GrayscalePolicy.isEnforced(1, 0))
        assertFalse(GrayscalePolicy.isEnforced(0, -1))
        assertFalse(GrayscalePolicy.isEnforced(null, null))
    }

    @Test
    fun `restore degrades unknown originals to plain off`() {
        assertEquals(GrayscalePolicy.Values(3, 11), GrayscalePolicy.restoreValues(3, 11))
        assertEquals(GrayscalePolicy.Values(0, GrayscalePolicy.MODE_DISABLED), GrayscalePolicy.restoreValues(null, null))
    }

    @Test
    fun `restore treats captured enforced pair as poisoned - never keeps grayscale on`() {
        assertEquals(
            GrayscalePolicy.Values(0, GrayscalePolicy.MODE_DISABLED),
            GrayscalePolicy.restoreValues(1, GrayscalePolicy.MODE_MONOCHROMACY),
        )
    }
}
