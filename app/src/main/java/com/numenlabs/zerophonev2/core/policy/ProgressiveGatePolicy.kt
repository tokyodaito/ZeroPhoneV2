package com.numenlabs.zerophonev2.core.policy

/**
 * Progressive gate friction: 60 s floor, +30 s per prior entry into the SAME
 * app today, ~3-minute ceiling. Only successful entries count — abandoning a
 * check must stay a free choice. Daily counters reset at local midnight.
 */
object ProgressiveGatePolicy {
    const val BASE_MILLIS: Long = 60_000L
    const val STEP_MILLIS: Long = 30_000L
    const val MAX_MILLIS: Long = 180_000L

    /** Required gate duration for an app with [entryCountToday] prior entries. */
    fun durationMillis(entryCountToday: Int): Long =
        (BASE_MILLIS + STEP_MILLIS * entryCountToday.coerceAtLeast(0)).coerceAtMost(MAX_MILLIS)

    /**
     * Prior entries into [packageName] today. A stored date that differs from
     * [todayIso] means the counter has rolled over — zero entries.
     */
    fun entriesToday(
        storedDate: String,
        counts: Map<String, Int>,
        packageName: String,
        todayIso: String,
    ): Int = if (storedDate == todayIso) counts[packageName] ?: 0 else 0
}
