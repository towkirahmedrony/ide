package com.agentx.app.model.ratelimit

import com.agentx.app.model.runSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A configured quota has to be in force after a restart, and it has to be in force
 * on the *first* request rather than after some later load completes: admission runs
 * on the request path and cannot wait.
 */
class RateLimitProfilePersistenceTest {

    private val configured = RateLimitProfile(
        providerId = "groq",
        modelId = "openai/gpt-oss-120b",
        accountId = "groq-a",
        requestsPerMinute = 2,
        source = RateLimitSource.APP_CONFIGURED,
    )

    /** A store that can only answer asynchronously, i.e. has no synchronous snapshot. */
    private class AsyncOnlyStore(private var values: List<RateLimitProfile> = emptyList()) : RateLimitProfileStore {
        var saves = 0
            private set

        override suspend fun load(): List<RateLimitProfile> = values

        override suspend fun save(profiles: List<RateLimitProfile>) {
            values = profiles
            saves += 1
        }
    }

    private suspend fun RateLimitProfileStore.snapshotSize() = snapshot().size

    @Test
    fun `a configured profile is written through to its store`() = runSuspend {
        val store = InMemoryRateLimitProfileStore()
        val manager = DefaultRateLimitManager(profileStore = store)

        manager.updateProfile(configured)

        assertEquals(listOf(configured), store.load())
    }

    @Test
    fun `a store that can answer synchronously has its quota in force from the first call`() = runSuspend {
        val store = InMemoryRateLimitProfileStore(listOf(configured))
        val manager = DefaultRateLimitManager(profileStore = store)

        // No restore() yet: the profile must already be counted against admission.
        assertNotNull(manager.profile("groq", "openai/gpt-oss-120b", "groq-a"))
        manager.reserve("groq", "openai/gpt-oss-120b", 1, 1, accountId = "groq-a")
        manager.reserve("groq", "openai/gpt-oss-120b", 1, 1, accountId = "groq-a")
        assertIs<RateLimitDecision.Blocked>(
            manager.canRequest("groq", "openai/gpt-oss-120b", 1, 1, accountId = "groq-a"),
        )
    }

    @Test
    fun `a store that can only load asynchronously is filled in by restore`() = runSuspend {
        val store = AsyncOnlyStore(listOf(configured))
        assertEquals(0, store.snapshotSize(), "this store has no synchronous snapshot to offer")

        val manager = DefaultRateLimitManager(profileStore = store)
        assertTrue(manager.profiles().isEmpty(), "nothing is known before the load runs")
        assertIs<RateLimitDecision.Allowed>(
            manager.canRequest("groq", "openai/gpt-oss-120b", 1, 1, accountId = "groq-a"),
            "an unloaded quota is unknown, not zero",
        )

        manager.restore()

        assertNotNull(manager.profile("groq", "openai/gpt-oss-120b", "groq-a"))
    }

    @Test
    fun `restore never overwrites profiles configured in this run`() = runSuspend {
        val store = InMemoryRateLimitProfileStore(
            listOf(configured.copy(requestsPerMinute = 1)),
        )
        val manager = DefaultRateLimitManager(profileStore = store)
        manager.updateProfile(configured.copy(requestsPerMinute = 7))

        manager.restore()

        assertEquals(7, assertNotNull(manager.profile("groq", "openai/gpt-oss-120b", "groq-a")).requestsPerMinute)
    }

    @Test
    fun `a provider with no stored profile stays unknown across a restart`() = runSuspend {
        val store = InMemoryRateLimitProfileStore(listOf(configured))
        val manager = DefaultRateLimitManager(profileStore = store)

        assertNull(manager.profile("gemini", "gemini-3.5-flash", null))
        assertIs<RateLimitDecision.Allowed>(
            manager.canRequest("gemini", "gemini-3.5-flash", 10, 10, accountId = "gemini-a"),
            "a provider nobody configured is not throttled by another provider's limits",
        )
    }
}
