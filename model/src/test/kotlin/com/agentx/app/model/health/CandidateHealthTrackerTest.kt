package com.agentx.app.model.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runtime candidate health and cooldowns.
 *
 * The two properties that matter: one candidate's failure must not disable more
 * than it actually reaches, and a transient failure must never become a permanent
 * ban.
 */
class CandidateHealthTrackerTest {

    private var now = 1_000L

    private fun tracker() = CandidateHealthTracker(clock = { now })

    // --- cooldown ----------------------------------------------------------

    @Test
    fun `a single failure degrades a candidate, repeated failures cool it down`() {
        val health = tracker()

        val first = health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)
        assertEquals(CandidateHealthState.DEGRADED, first.state)
        // Degraded still means "worth attempting".
        assertTrue(health.isUsable("groq", "groq-y"))

        val second = health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)
        assertEquals(CandidateHealthState.TEMPORARILY_UNAVAILABLE, second.state)
        assertFalse(health.isUsable("groq", "groq-y"))
        assertNotNull(health.cooldownRemainingMillis("groq", "groq-y"))
        Unit
    }

    @Test
    fun `a cooled-down candidate becomes eligible again and the record is pruned`() {
        val health = tracker()
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)
        val cooldown = assertNotNull(health.cooldownRemainingMillis("groq", "groq-y"))

        // One millisecond before the cooldown ends the candidate is still parked.
        now += cooldown - 1L
        assertFalse(health.isUsable("groq", "groq-y"))

        now += 1L
        assertTrue(health.isUsable("groq", "groq-y"), "a transient failure must not become a permanent ban")
        health.prune(now)
        assertEquals(CandidateHealthState.UNKNOWN, health.state("groq", "groq-y").state)
        assertTrue(health.snapshot().isEmpty())
    }

    @Test
    fun `the provider's retry signal wins over the local backoff`() {
        val health = tracker()
        // Two failures would normally produce a small local backoff; the provider said
        // ninety seconds, so that is what is respected.
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.RATE_LIMITED, retryAfterMillis = 90_000L)

        val remaining = assertNotNull(health.cooldownRemainingMillis("groq", "groq-y"))
        assertEquals(90_000L, remaining)
        assertEquals(CandidateHealthState.RATE_LIMITED, health.state("groq", "groq-y").state)
    }

    @Test
    fun `the local backoff grows but stays bounded`() {
        val health = tracker()

        val waits = (1..8).map { attempt ->
            health.recordFailure("groq", "groq-y", failure = CandidateFailure.CONNECTION)
            health.cooldownRemainingMillis("groq", "groq-y") ?: 0L
        }

        // It grows (so a persistent problem is not retried at full speed) and never
        // exceeds the ceiling (so the candidate is retried eventually).
        assertTrue(waits.last() >= waits.first())
        assertTrue(waits.all { it <= CandidateHealthTracker.DEFAULT_MAX_COOLDOWN_MILLIS })
        assertTrue(waits.last() > 0L)
    }

    @Test
    fun `success clears the cooldown`() {
        val health = tracker()
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)
        assertFalse(health.isUsable("groq", "groq-y"))

        val record = health.recordSuccess("groq", "groq-y")

        assertEquals(CandidateHealthState.HEALTHY, record.state)
        assertTrue(health.isUsable("groq", "groq-y"))
        assertNull(health.cooldownRemainingMillis("groq", "groq-y"))
    }

    // --- scope -------------------------------------------------------------

    @Test
    fun `a model-specific failure does not disable the provider's other models`() {
        val health = tracker()
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)

        assertFalse(health.isUsable("groq", "groq-y"))
        // A different model of the same provider is untouched: one bad model is not a
        // provider outage.
        assertTrue(health.isUsable("groq", "groq-other"))
        assertEquals(CandidateHealthState.UNKNOWN, health.state("groq", "groq-other").state)
        assertTrue(health.isUsable("cerebras", "cerebras-z"))
    }

    @Test
    fun `rejected credentials reach the whole provider`() {
        val health = tracker()

        val record = health.recordFailure("groq", "groq-y", failure = CandidateFailure.AUTHENTICATION)

        assertEquals(CandidateHealthState.AUTH_FAILED, record.state)
        assertEquals(CandidateHealthScope.PROVIDER, record.scope)
        // Every model of that provider is affected, because the credentials are the
        // provider's, not one model's.
        assertFalse(health.isUsable("groq", "groq-y"))
        assertFalse(health.isUsable("groq", "groq-other"))
        // A different provider is unaffected.
        assertTrue(health.isUsable("cerebras", "cerebras-z"))
    }

    @Test
    fun `a model success does not erase a provider-wide problem`() {
        val health = tracker()
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.CONFIGURATION)

        health.recordSuccess("groq", "groq-other")

        // The configuration problem is the provider's, so one model answering does not
        // clear it.
        assertFalse(health.isUsable("groq", "groq-y"))
    }

    @Test
    fun `a permanent failure does not create a cooldown`() {
        val health = tracker()

        val record = health.recordFailure("groq", "groq-y", failure = CandidateFailure.PERMANENT)

        // Retrying a permanent failure changes nothing, so no wait is imposed; the
        // state simply is not mistaken for healthy.
        assertNull(record.cooldownUntilMillis)
        assertTrue(health.isUsable("groq", "groq-y"))
    }

    @Test
    fun `the snapshot is ordered and carries no credential`() {
        val health = tracker()
        health.recordFailure("groq", "groq-y", failure = CandidateFailure.TIMEOUT)
        now += 5L
        health.recordFailure("cerebras", "cerebras-z", failure = CandidateFailure.OVERLOADED)

        val snapshot = health.snapshot()

        assertEquals(2, snapshot.size)
        assertEquals("cerebras", snapshot.first().providerId)
        assertTrue(snapshot.all { it.reason == null || !it.reason!!.contains("key", ignoreCase = true) })
    }
}
