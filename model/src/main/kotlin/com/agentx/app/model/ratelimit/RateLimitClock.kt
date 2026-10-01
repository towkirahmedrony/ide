package com.agentx.app.model.ratelimit

import kotlinx.coroutines.delay

/**
 * Time and waiting, injected so admission and backoff can be tested
 * deterministically instead of depending on real minute/day delays.
 *
 * [sleep] must be cancellable: cancelling the calling coroutine has to stop a
 * pending wait immediately.
 */
interface RateLimitClock {
    fun nowMillis(): Long

    suspend fun sleep(millis: Long)
}

/** Production clock backed by the wall clock and [delay]. */
class SystemRateLimitClock : RateLimitClock {
    override fun nowMillis(): Long = System.currentTimeMillis()

    override suspend fun sleep(millis: Long) {
        if (millis > 0) delay(millis)
    }
}
