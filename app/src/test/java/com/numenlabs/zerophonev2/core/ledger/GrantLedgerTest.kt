package com.numenlabs.zerophonev2.core.ledger

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantLedgerTest {
    @Test
    fun `open computes deadline as now plus duration`() {
        val grant = GrantLedger.open("com.example.app", nowEffectiveMillis = 1_000, durationMillis = 300_000, id = "id1")
        assertEquals("com.example.app", grant.packageName)
        assertEquals(1_000, grant.openedAtMillis)
        assertEquals(301_000, grant.deadlineMillis)
    }

    @Test
    fun `isActive boundaries`() {
        val grant = GrantLedger.open("pkg", 0, 300_000, "id")
        assertTrue(GrantLedger.isActive(grant, 299_999))
        assertFalse(GrantLedger.isActive(grant, 300_000)) // at deadline = expired
        assertFalse(GrantLedger.isActive(grant, 400_000))
        assertFalse(GrantLedger.isActive(null, 0))
    }

    @Test
    fun `remaining is null when absent or expired`() {
        val grant = GrantLedger.open("pkg", 0, 300_000, "id")
        assertEquals(1L, GrantLedger.remainingMillis(grant, 299_999))
        assertNull(GrantLedger.remainingMillis(grant, 300_000))
        assertNull(GrantLedger.remainingMillis(null, 0))
    }

    @Test
    fun `serialization round trip`() {
        val grant = GrantLedger.open("com.example.app", 5, 300_000, "uuid-42")
        val json = Json.encodeToString(ActiveGrant.serializer(), grant)
        val back = Json.decodeFromString(ActiveGrant.serializer(), json)
        assertEquals(grant, back)
    }

    @Test
    fun `media hold extends only near deadline while playing and enabled`() {
        val grant = GrantLedger.open("pkg", 0, 300_000, "id") // deadline at 300k
        // Far from deadline — no extension.
        assertFalse(
            GrantLedger.shouldExtendWhileMedia(grant, 0, mediaPlaying = true, pauseOnMediaEnabled = true),
        )
        // Near deadline + playing + enabled — extend.
        assertTrue(
            GrantLedger.shouldExtendWhileMedia(grant, 300_000 - 60_000, mediaPlaying = true, pauseOnMediaEnabled = true),
        )
        // Not playing — never.
        assertFalse(
            GrantLedger.shouldExtendWhileMedia(grant, 300_000 - 60_000, mediaPlaying = false, pauseOnMediaEnabled = true),
        )
        // Feature disabled — never.
        assertFalse(
            GrantLedger.shouldExtendWhileMedia(grant, 300_000 - 60_000, mediaPlaying = true, pauseOnMediaEnabled = false),
        )
        // Per-session grants have no timer to hold.
        val session = GrantLedger.open("pkg", 0, 300_000, "id2", perSession = true)
        assertFalse(
            GrantLedger.shouldExtendWhileMedia(session, 0, mediaPlaying = true, pauseOnMediaEnabled = true),
        )
    }

    @Test
    fun `extendWhileMedia rolls deadline and flags hold`() {
        val grant = GrantLedger.open("pkg", 0, 300_000, "id")
        val extended = GrantLedger.extendWhileMedia(grant)
        assertEquals(300_000 + GrantLedger.MEDIA_EXTENSION_MILLIS, extended.deadlineMillis)
        assertTrue(extended.mediaHold)
        assertFalse(grant.mediaHold)
    }
}
