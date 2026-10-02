package com.agentx.app.model.ratelimit

import kotlin.coroutines.cancellation.CancellationException

/** Deterministic clock: sleeping advances virtual time instead of real time. */
internal class FakeRateLimitClock(private var now: Long = 0L) : RateLimitClock {
    val sleeps = mutableListOf<Long>()

    override fun nowMillis(): Long = now

    override suspend fun sleep(millis: Long) {
        sleeps += millis
        now += millis
    }
}

/** Clock that cancels the waiter after [cancelAfter] sleeps. */
internal class CancellingRateLimitClock(
    private var now: Long = 0L,
    private val cancelAfter: Int,
) : RateLimitClock {
    var sleeps = 0

    override fun nowMillis(): Long = now

    override suspend fun sleep(millis: Long) {
        sleeps += 1
        if (sleeps >= cancelAfter) throw CancellationException("test cancellation")
        now += millis
    }
}

internal fun request(
    providerId: String = "groq",
    modelId: String = "llama-3.3-70b-versatile",
    inputTokens: Int = 10,
    outputTokens: Int = 10,
    rateLimited: Boolean = true,
): RateLimitRequest = RateLimitRequest(
    providerId = providerId,
    modelId = modelId,
    estimatedInputTokens = inputTokens,
    estimatedOutputTokens = outputTokens,
    rateLimited = rateLimited,
)
