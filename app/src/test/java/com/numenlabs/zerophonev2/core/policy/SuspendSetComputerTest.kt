package com.numenlabs.zerophonev2.core.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class SuspendSetComputerTest {
    @Test
    fun `computeSuspendSet filters self, protected and prefixes`() {
        val set =
            SuspendSetComputer.computeSuspendSet(
                distractingPackages =
                    setOf(
                        "com.instagram.android",
                        "com.numenlabs.zerophonev2", // self — never
                        "com.android.chrome", // protected prefix — never
                        "com.android.settings", // protected package — never
                        "com.example.game",
                    ),
                selfPackage = "com.numenlabs.zerophonev2",
            )
        assertEquals(setOf("com.instagram.android", "com.example.game"), set)
    }

    @Test
    fun `extra dynamic protected packages are excluded`() {
        val set =
            SuspendSetComputer.computeSuspendSet(
                distractingPackages = setOf("com.instagram.android", "some.ime"),
                selfPackage = "me",
                protectedPackages = SuspendSetComputer.DEFAULT_PROTECTED_PACKAGES + setOf("some.ime"),
            )
        assertEquals(setOf("com.instagram.android"), set)
    }

    @Test
    fun `release set is previous minus current`() {
        val release =
            SuspendSetComputer.computeReleaseSet(
                lastSuspended = setOf("a", "b", "c"),
                suspendSet = setOf("b", "c", "d"),
            )
        assertEquals(setOf("a"), release)
    }

    @Test
    fun `full release unions everything we may have touched`() {
        val full =
            SuspendSetComputer.computeFullReleaseSet(
                lastSuspended = setOf("a", "b"),
                distractingPackages = setOf("c"),
                activeGrantPackage = "d",
            )
        assertEquals(setOf("a", "b", "c", "d"), full)
    }
}
