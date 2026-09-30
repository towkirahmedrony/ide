package com.agentx.app.model.runtime

import com.agentx.app.core.timeout.AgentTimeouts

/**
 * Bounds for every connection operation.
 *
 * These exist so no state can be transient forever and so recovery never turns
 * into aggressive polling: attempts are finite, the delay between them grows,
 * and one overall timeout caps the whole operation.
 */
data class ModelConnectionPolicy(
    /** Attempts when the user (or app start) asks for a model to come online. */
    val maxStartAttempts: Int = 6,

    /** Attempts for an explicit or automatic reconnect. */
    val maxReconnectAttempts: Int = 5,

    val initialBackoffMillis: Long = 700,
    val maxBackoffMillis: Long = 8_000,
    val backoffMultiplier: Double = 2.0,

    /**
     * Hard cap for a single connect operation, so nothing hangs.
     *
     * It follows the central model budget rather than an arbitrary two minutes:
     * bringing up a cold local runtime (a hosted tunnel, a freshly started Colab
     * session) legitimately takes minutes, and the previous 120s cap reported that
     * as "model offline".
     */
    val operationTimeoutMillis: Long = AgentTimeouts.MODEL_REQUEST_MILLIS,

    /** Cap for one health request; the transport timeout is the outer bound. */
    val healthTimeoutMillis: Long = 10_000,
) {
    init {
        require(maxStartAttempts >= 1) { "maxStartAttempts must be at least 1" }
        require(maxReconnectAttempts >= 1) { "maxReconnectAttempts must be at least 1" }
        require(initialBackoffMillis >= 0) { "backoff must not be negative" }
        require(maxBackoffMillis >= initialBackoffMillis) { "maxBackoffMillis must be >= initialBackoffMillis" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be at least 1" }
        require(operationTimeoutMillis > 0) { "operationTimeoutMillis must be positive" }
        require(healthTimeoutMillis > 0) { "healthTimeoutMillis must be positive" }
    }

    /** Delay before attempt [attempt] + 1, growing until [maxBackoffMillis]. */
    fun backoffMillis(attempt: Int): Long {
        if (attempt < 1) return initialBackoffMillis
        var delay = initialBackoffMillis.toDouble()
        repeat(attempt - 1) { delay *= backoffMultiplier }
        return delay.coerceAtMost(maxBackoffMillis.toDouble()).toLong()
    }
}
