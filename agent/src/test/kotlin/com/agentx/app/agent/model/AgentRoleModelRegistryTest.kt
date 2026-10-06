package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.testDomain
import com.agentx.app.model.ModelConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registry is the one authoritative role → model configuration. These tests
 * cover the defaults, their persistence, and that what Settings saves is what
 * [AgentModelResolver] actually selects.
 */
class AgentRoleModelRegistryTest {

    private fun connection(providerId: String, model: String) = ModelConfig(
        providerId = providerId,
        baseUrl = "https://$providerId.example/v1",
        model = model,
        connectionKind = testDomain(providerId),
    )

    private fun registry(store: AgentRoleModelStore = InMemoryAgentRoleModelStore()) =
        AgentRoleModelRegistry(DefaultAgentRoleModelRepository(store))

    private fun resolver(registry: AgentRoleModelRegistry, connections: Map<String, ModelConfig>) =
        AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { connections },
            livePreferences = { registry.preferences() },
        )

    // --- defaults ----------------------------------------------------------

    @Test
    fun `defaults match the target role mapping`() {
        val registry = registry()
        assertEquals(
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
            registry.selection(AgentRole.MAIN).providerId,
        )
        assertEquals(AgentModelProviders.FREELMAPI, registry.selection(AgentRole.EXPLORER).providerId)
        assertEquals(AgentModelProviders.FREELMAPI, registry.selection(AgentRole.RESEARCHER).providerId)
        assertEquals(
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
            registry.selection(AgentRole.CODER).providerId,
        )
        assertEquals(
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
            registry.selection(AgentRole.DEBUGGER).providerId,
        )
        assertEquals(AgentModelProviders.FREELMAPI, registry.selection(AgentRole.REVIEWER).providerId)
        assertEquals(AgentModelProviders.GROQ, registry.selection(AgentRole.TESTER).providerId)
        assertEquals(AgentModelIds.DEVSTRAL_24B, registry.selection(AgentRole.CODER).model)
    }

    @Test
    fun `existing installations without role settings keep the built-in defaults`() {
        val registry = registry(InMemoryAgentRoleModelStore())
        runBlocking { registry.load() }
        assertNull(registry.override(AgentRole.CODER))
        assertEquals(AgentModelIds.DEVSTRAL_24B, registry.selection(AgentRole.CODER).model)
        assertEquals(
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
            registry.selection(AgentRole.MAIN).providerId,
        )
    }

    // --- persistence -------------------------------------------------------

    @Test
    fun `a saved role choice is restored from the store`() {
        val store = InMemoryAgentRoleModelStore()
        val first = registry(store)
        runBlocking {
            first.save(AgentRole.CODER, AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "devstral-24b")
        }

        val restored = registry(store)
        runBlocking { restored.load() }

        val selection = restored.selection(AgentRole.CODER)
        assertEquals("devstral-24b", selection.model)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, selection.providerId)
        assertTrue(selection.explicit)
    }

    @Test
    fun `reset restores the built-in default`() {
        val store = InMemoryAgentRoleModelStore()
        val registry = registry(store)
        runBlocking {
            registry.save(AgentRole.MAIN, AgentModelProviders.GROQ, "llama-3.1-8b-instant")
            registry.reset(AgentRole.MAIN)
        }
        assertNull(registry.override(AgentRole.MAIN))
        assertEquals(
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
            registry.selection(AgentRole.MAIN).providerId,
        )
    }

    // --- settings reach the resolver --------------------------------------

    @Test
    fun `changing the main agent model changes what the resolver selects`() {
        val registry = registry()
        val connections = mapOf(
            AgentModelProviders.GEMINI to connection(AgentModelProviders.GEMINI, "gemini-1.5-flash"),
        )
        val active = connection(AgentModelProviders.GEMINI, "gemini-3.5-flash")
        val resolver = resolver(registry, connections)

        runBlocking { registry.save(AgentRole.MAIN, AgentModelProviders.GEMINI, "gemini-3.5-flash") }
        assertEquals("gemini-3.5-flash", resolver.resolve(AgentRole.MAIN, active).model)

        runBlocking { registry.save(AgentRole.MAIN, AgentModelProviders.GEMINI, "gemini-1.5-pro") }
        assertEquals("gemini-1.5-pro", resolver.resolve(AgentRole.MAIN, active).model)
    }

    @Test
    fun `changing the coder model changes what the resolver selects`() {
        val registry = registry()
        val local = connection(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "devstral-24b")
        val active = connection(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "devstral-24b")
        val resolver = resolver(registry, mapOf(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL to local))

        runBlocking {
            registry.save(AgentRole.CODER, AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "qwen2.5-coder-32b")
        }
        assertEquals("qwen2.5-coder-32b", resolver.resolve(AgentRole.CODER, active).model)
    }

    @Test
    fun `a local role and an api role resolve different connections`() {
        val registry = registry()
        val connections = mapOf(
            AgentModelProviders.FREELMAPI to connection(AgentModelProviders.FREELMAPI, "gemini-3.5-flash"),
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL to
                connection(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "devstral-24b"),
        )
        val resolver = resolver(registry, connections)
        val active = connection(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "devstral-24b")

        val coder = resolver.resolve(AgentRole.CODER, active)
        val reviewer = resolver.resolve(AgentRole.REVIEWER, active)

        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, coder.providerId)
        assertEquals(AgentModelProviders.FREELMAPI, reviewer.providerId)
        assertNotEquals(coder.providerId, reviewer.providerId)
    }

    @Test
    fun `explorer and reviewer resolve independently on the same provider`() {
        val registry = registry()
        val freeLlm = connection(AgentModelProviders.FREELMAPI, "gemini-3.5-flash")
        val resolver = resolver(registry, mapOf(AgentModelProviders.FREELMAPI to freeLlm))

        runBlocking { registry.save(AgentRole.EXPLORER, AgentModelProviders.FREELMAPI, "llama-3.1-8b-instant") }

        val active = connection(AgentModelProviders.FREELMAPI, "gemini-3.5-flash")
        val explorer = resolver.resolve(AgentRole.EXPLORER, active)
        val reviewer = resolver.resolve(AgentRole.REVIEWER, active)
        assertEquals("llama-3.1-8b-instant", explorer.model)
        assertEquals(AgentModelIds.FREELLMAPI_GEMINI, reviewer.model)
    }

    @Test
    fun `a domain-bound role is not silently answered by an unrelated active model`() {
        val registry = registry()
        val resolver = resolver(registry, emptyMap())
        val active = connection(AgentModelProviders.GEMINI, "gemini-3.5-flash")

        // Coder's built-in mapping is bound to the local domain, so an unrelated
        // API active model must not stand in for it; the run fails instead.
        assertFailsWith<AgentModelResolutionException> { resolver.resolve(AgentRole.CODER, active) }
        // The role configuration itself is preserved, not substituted.
        assertNotNull(registry.selection(AgentRole.CODER))
    }

    @Test
    fun `a legacy role preference without a domain keeps the active model as compatibility`() {
        val resolver = AgentModelResolver(
            preferences = AgentModelPreferences()
                .with(AgentRole.CODER, RoleModelPreference(AgentModelProviders.CEREBRAS, "cerebras-1")),
            connections = { emptyMap() },
        )
        val active = connection(AgentModelProviders.GEMINI, "gemini-3.5-flash")

        // A preference with no execution domain and no explicit assignment keeps the
        // documented compatibility fallback to the active model.
        assertEquals(active, resolver.resolve(AgentRole.CODER, active))
    }

    // --- availability / validation ----------------------------------------

    @Test
    fun `an unconnected provider is reported as not configured`() {
        val selection = registry().selection(AgentRole.MAIN)
        val status = RoleModelEvaluation.evaluate(selection, options = emptyList())
        assertEquals(RoleModelState.NOT_CONFIGURED, status.state)
        assertEquals("OpenAI-compatible", status.providerLabel)
    }

    @Test
    fun `a connected provider reports connected`() {
        val selection = registry().selection(AgentRole.MAIN)
        val options = listOf(
            ProviderModelOption(
                providerId = AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
                providerLabel = "OpenAI-compatible",
                models = listOf(AgentModelIds.DEVSTRAL_24B),
                connected = true,
            ),
        )
        val status = RoleModelEvaluation.evaluate(selection, options)
        assertEquals(RoleModelState.CONNECTED, status.state)
        assertEquals(AgentModelIds.DEVSTRAL_24B, status.model)
    }

    @Test
    fun `a removed model is reported as unavailable and not substituted`() {
        val selection = RoleModelSelection(
            role = AgentRole.CODER,
            providerId = AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
            model = "devstral-24b",
            connectionId = "preset-1",
            explicit = true,
        )
        val options = listOf(
            ProviderModelOption(
                providerId = AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
                providerLabel = "OpenAI-compatible",
                models = listOf("qwen2.5-coder-7b"),
                connected = true,
            ),
        )
        val status = RoleModelEvaluation.evaluate(selection, options)
        assertEquals(RoleModelState.MODEL_UNAVAILABLE, status.state)
        assertEquals("devstral-24b", status.model)
        assertTrue(status.message.contains("devstral-24b"))
    }

    @Test
    fun `a removed provider keeps the stored reference and is reported`() {
        val store = InMemoryAgentRoleModelStore()
        val registry = registry(store)
        runBlocking {
            registry.save(AgentRole.DEBUGGER, AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "devstral-24b")
            registry.load()
        }
        // The provider is gone from the catalog entirely.
        val status = RoleModelEvaluation.evaluate(registry.selection(AgentRole.DEBUGGER), emptyList())
        assertEquals(RoleModelState.NOT_CONFIGURED, status.state)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, status.providerId)
        // The saved reference is kept, never silently replaced.
        assertEquals(
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
            registry.override(AgentRole.DEBUGGER)?.providerId,
        )
    }
}
