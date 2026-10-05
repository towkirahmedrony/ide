package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The configuration source the runtime was missing.
 *
 * [ModelFallbackPolicy] could always describe a chain and [ModelFallback] could
 * always execute one, but nothing could configure them: the composition root built
 * the agent layer disabled and no store existed. A policy nobody can set is not a
 * feature, so these tests cover that a configured chain survives a reload, that it
 * reaches the executable policy, and that the opt-in baseline is preserved.
 */
class AgentFallbackConfigTest {

    private val coder = RoleModelPreference(providerId = "groq", model = "llama-3.3-70b-versatile")

    @Test
    fun `the default configuration preserves the opt-in baseline`() {
        val config = AgentFallbackConfig.DEFAULT

        assertFalse(config.automaticFallback, "fallback must not be enabled for existing users")
        assertTrue(config.chains.isEmpty())
        assertEquals(ModelFallbackPolicy.DISABLED, config.toPolicy())
        assertFalse(config.enabledFor(AgentRole.CODER))
    }

    @Test
    fun `a configured chain survives a reload`() {
        val chain = listOf(
            RoleModelPreference(providerId = "cerebras", model = "qwen-3-coder", connectionId = "cerebras-a"),
            RoleModelPreference(providerId = "groq", model = "openai/gpt-oss-120b", connectionId = "groq-b"),
        )
        val config = AgentFallbackConfig.DEFAULT
            .copy(automaticFallback = true)
            .withChain(AgentRole.CODER, chain)

        val reloaded = AgentFallbackConfigCodec.decode(AgentFallbackConfigCodec.encode(config))

        assertEquals(config, reloaded)
        assertEquals(chain, reloaded.chain(AgentRole.CODER))
        assertTrue(reloaded.enabledFor(AgentRole.CODER))
    }

    @Test
    fun `the executable policy carries the same chains that were configured`() {
        val config = AgentFallbackConfig.DEFAULT
            .copy(automaticFallback = true, maxFallbackAttempts = 1)
            .withChain(AgentRole.CODER, listOf(coder))

        val policy = config.toPolicy()

        assertEquals(true, policy.automaticFallback)
        assertEquals(1, policy.maxFallbackAttempts)
        assertEquals(listOf(coder), policy.candidates(AgentRole.CODER))
        assertTrue(policy.enabledFor(AgentRole.CODER))
        // A role with no chain is not silently given one.
        assertFalse(policy.enabledFor(AgentRole.REVIEWER))
    }

    @Test
    fun `an empty chain removes the role instead of keeping an empty entry`() {
        val config = AgentFallbackConfig.DEFAULT
            .withChain(AgentRole.CODER, listOf(coder))
            .withChain(AgentRole.CODER, emptyList())

        assertTrue(config.chains.isEmpty())
        assertFalse(config.enabledFor(AgentRole.CODER))
    }

    @Test
    fun `unreadable stored configuration falls back to disabled rather than to something enabled`() {
        listOf("", "not json", "[]", """{"chains":{"CODER":"nope"}}""").forEach { text ->
            assertEquals(
                AgentFallbackConfig.DEFAULT,
                AgentFallbackConfigCodec.decode(text),
                "a malformed record must never enable a chain nobody configured: '$text'",
            )
        }
    }

    @Test
    fun `an unknown role or malformed entry in storage is dropped, not guessed`() {
        val text = """
            {"automaticFallback":true,"chains":{
              "NOT_A_ROLE":[{"providerId":"groq"}],
              "CODER":[{"providerId":""},{"providerId":"groq","model":"m"}]
            }}
        """.trimIndent()

        val config = AgentFallbackConfigCodec.decode(text)

        assertTrue(config.chain(AgentRole.CODER).size == 1, "the usable entry is kept")
        assertEquals("groq", config.chain(AgentRole.CODER).single().providerId)
        assertEquals(1, config.chains.size, "an unknown role key adds no chain")
    }

    @Test
    fun `a stored attempt bound is clamped to the architecture limit`() {
        val oversized = AgentFallbackConfigCodec.decode("""{"automaticFallback":true,"maxFallbackAttempts":99}""")

        assertEquals(AgentFallbackConfig.MAX_FALLBACK_ATTEMPTS_LIMIT, oversized.maxFallbackAttempts)
    }

    @Test
    fun `the repository persists, reloads and applies without a restart`() = kotlinx.coroutines.runBlocking {
        val store = InMemoryAgentFallbackStore()
        val repository = AgentFallbackConfigRepository(store)
        assertFalse(repository.enabledFor(AgentRole.CODER), "starts at the opt-in baseline")

        repository.setChain(AgentRole.CODER, listOf(coder))
        repository.setEnabled(true)

        // A live policy reads through, so a saved change applies to the next request.
        val live = repository.livePolicy()
        assertTrue(live().enabledFor(AgentRole.CODER))

        // And the change is in the store, so it survives a restart.
        val restarted = AgentFallbackConfigRepository(store)
        assertTrue(restarted.enabledFor(AgentRole.CODER))
        assertEquals(listOf(coder), restarted.config().chain(AgentRole.CODER))
    }

    @Test
    fun `disabling keeps the configured chains so toggling is not destructive`() = kotlinx.coroutines.runBlocking {
        val repository = AgentFallbackConfigRepository(InMemoryAgentFallbackStore())
        repository.setChain(AgentRole.CODER, listOf(coder))
        repository.setEnabled(true)

        repository.setEnabled(false)
        assertFalse(repository.enabledFor(AgentRole.CODER))
        assertEquals(listOf(coder), repository.config().chain(AgentRole.CODER))
        assertTrue(repository.policy().candidates(AgentRole.CODER).isNotEmpty())

        repository.setEnabled(true)
        assertTrue(repository.enabledFor(AgentRole.CODER))
    }

    @Test
    fun `reset returns to the opt-in baseline`() = kotlinx.coroutines.runBlocking {
        val store = InMemoryAgentFallbackStore()
        val repository = AgentFallbackConfigRepository(store)
        repository.setChain(AgentRole.CODER, listOf(coder))
        repository.setEnabled(true)

        repository.resetAll()

        assertEquals(AgentFallbackConfig.DEFAULT, repository.config())
        assertFalse(AgentFallbackConfigRepository(store).enabledFor(AgentRole.CODER))
    }

    @Test
    fun `a stored chain never contains a credential`() {
        val encoded = AgentFallbackConfigCodec.encode(
            AgentFallbackConfig.DEFAULT
                .copy(automaticFallback = true)
                .withChain(AgentRole.CODER, listOf(coder)),
        )

        listOf("apikey", "api_key", "authorization", "bearer", "secret", "password", "credential").forEach { forbidden ->
            assertTrue(
                !encoded.lowercase().contains(forbidden),
                "a fallback chain names a connection, never a secret: found '$forbidden'",
            )
        }
    }
}
