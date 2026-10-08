package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.testDomain
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.manager.ModelConnectionKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A role's model identity is `execution domain + connectionId + modelId`, never a
 * provider family on its own.
 *
 * These tests drive the real [AgentRoleModelRegistry] and [AgentModelResolver]
 * — the same pair the app wires together — with a local runtime and a FreeLLMAPI
 * gateway that share the OpenAI-compatible protocol. No provider is contacted and
 * the resolver is pure, so the assertions describe exactly what a running agent
 * would receive.
 *
 * Covered here: the local roles stay local, the API roles reach their exact
 * FreeLLMAPI model, two connections of one family stay distinct, a missing exact
 * target fails instead of being substituted, adding API connections moves nothing,
 * and a saved assignment survives a reload intact.
 */
class RoleModelResolutionDeterminismTest {

    private val localFamily = AgentModelProviders.OPENAI_COMPATIBLE_LOCAL
    private val devstral = AgentModelIds.DEVSTRAL_24B

    private fun connection(
        connectionId: String,
        providerId: String,
        model: String,
        domain: ModelConnectionKind = testDomain(providerId),
    ) = ModelConfig(
        providerId = providerId,
        baseUrl = "https://$connectionId.example/v1",
        model = model,
        connectionId = connectionId,
        connectionKind = domain,
    )

    /** The local model runtime MAIN/CODER/DEBUGGER run on. */
    private val local = connection("local-devstral", localFamily, devstral)

    /** The FreeLLMAPI gateway connection, addressed by connection identity. */
    private val freeLlm = connection(
        connectionId = "freellmapi-gateway",
        providerId = AgentModelProviders.FREELMAPI,
        model = AgentModelIds.FREELLMAPI_GEMINI,
    )

    /**
     * A remote endpoint that uses the *same* OpenAI-compatible family as the local
     * runtime. It is the collision the audit found: same provider identity, different
     * execution domain.
     */
    private val remoteOpenAiCompatible = connection(
        connectionId = "remote-oai",
        providerId = localFamily,
        model = "qwen2.5-coder-14b",
        domain = ModelConnectionKind.API,
    )

    private fun registry(store: AgentRoleModelStore = InMemoryAgentRoleModelStore()) =
        AgentRoleModelRegistry(DefaultAgentRoleModelRepository(store))

    private fun resolver(registry: AgentRoleModelRegistry, connections: Map<String, ModelConfig>) =
        AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { connections },
            livePreferences = { registry.preferences() },
        )

    private fun connections(vararg configs: ModelConfig): Map<String, ModelConfig> =
        configs.associateBy { it.connectionId }

    private fun assertResolvesToLocal(resolver: AgentModelResolver, role: AgentRole) {
        // The active model is deliberately an API one: it must not stand in for a
        // local role just because it is what the session happens to be running.
        val resolved = resolver.resolve(role, default = freeLlm)
        assertEquals(local.connectionId, resolved.connectionId, "$role must stay on the local connection")
        assertEquals(localFamily, resolved.providerId, "$role must stay on the local provider identity")
        assertEquals(devstral, resolved.model, "$role must stay on the local model")
        assertEquals(ModelConnectionKind.LOCAL_CUSTOM, resolved.connectionKind, "$role must stay local")
    }

    // --- A/B/C. local isolation --------------------------------------------

    @Test
    fun `A MAIN resolves to the explicit local connection, not to FreeLLMAPI`() {
        val resolver = resolver(registry(), connections(local, freeLlm))

        assertResolvesToLocal(resolver, AgentRole.MAIN)
    }

    @Test
    fun `B CODER resolves to the explicit local connection, not to FreeLLMAPI`() {
        val resolver = resolver(registry(), connections(local, freeLlm))

        assertResolvesToLocal(resolver, AgentRole.CODER)
    }

    @Test
    fun `C DEBUGGER resolves to the explicit local connection, not to FreeLLMAPI`() {
        val resolver = resolver(registry(), connections(local, freeLlm))

        assertResolvesToLocal(resolver, AgentRole.DEBUGGER)
    }

    // --- D. API explicit routing -------------------------------------------

    @Test
    fun `D an API role resolves the exact FreeLLMAPI connection and model it was configured for`() {
        val registry = registry()
        runBlocking {
            registry.save(
                AgentRole.REVIEWER,
                AgentModelProviders.FREELMAPI,
                AgentModelIds.FREELLMAPI_GEMINI,
                freeLlm.connectionId,
            )
            registry.save(
                AgentRole.EXPLORER,
                AgentModelProviders.FREELMAPI,
                AgentModelIds.FREELLMAPI_GROQ,
                freeLlm.connectionId,
            )
        }
        // The local connection is connected too, and is the active model.
        val resolver = resolver(registry, connections(local, freeLlm))

        val reviewer = resolver.resolve(AgentRole.REVIEWER, default = local)
        val explorer = resolver.resolve(AgentRole.EXPLORER, default = local)

        assertEquals(freeLlm.connectionId, reviewer.connectionId)
        assertEquals(AgentModelIds.FREELLMAPI_GEMINI, reviewer.model, "the Reviewer runs the configured Gemini model")
        assertEquals(ModelConnectionKind.API, reviewer.connectionKind)

        assertEquals(freeLlm.connectionId, explorer.connectionId)
        assertEquals(AgentModelIds.FREELLMAPI_GROQ, explorer.model, "the Explorer runs the configured Groq model")
        assertEquals(ModelConnectionKind.API, explorer.connectionKind)
    }

    @Test
    fun `D an API role configured by provider identity still names its own concrete model`() {
        val registry = registry()
        // A family-only assignment (no connection id): the role still names the model
        // it wants, and the domain keeps it on the API side of the gateway.
        runBlocking {
            registry.save(AgentRole.RESEARCHER, AgentModelProviders.FREELMAPI, AgentModelIds.FREELLMAPI_GEMINI)
        }
        val resolver = resolver(registry, connections(local, freeLlm))

        val resolved = resolver.resolve(AgentRole.RESEARCHER, default = local)

        assertEquals(freeLlm.connectionId, resolved.connectionId)
        assertEquals(AgentModelIds.FREELLMAPI_GEMINI, resolved.model)
    }

    // --- E. same provider family isolation ---------------------------------

    @Test
    fun `E a role bound to connection A never resolves to connection B of the same family`() {
        // Both connections are local OpenAI-compatible and expose the *same* model id,
        // so only the connection identity can tell them apart.
        val a = connection("oai-a", localFamily, "qwen2.5-coder-14b")
        val b = connection("oai-b", localFamily, "qwen2.5-coder-14b")
        val registry = registry()
        runBlocking { registry.save(AgentRole.CODER, localFamily, "qwen2.5-coder-14b", a.connectionId) }
        val resolver = resolver(registry, connections(a, b))

        val resolved = resolver.resolve(AgentRole.CODER, default = b)

        assertEquals(a.connectionId, resolved.connectionId, "the saved connection identity is honored")
        assertEquals("qwen2.5-coder-14b", resolved.model)
    }

    @Test
    fun `E a saved assignment is bound to its execution domain, not to its provider family`() {
        // Saved without a connection id: the assignment is identified by the provider
        // identity plus its domain. The only connection of that family is remote, and
        // it must not become the role's model.
        val registry = registry()
        runBlocking { registry.save(AgentRole.CODER, localFamily, devstral) }
        val resolver = resolver(registry, connections(remoteOpenAiCompatible))

        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver.resolve(AgentRole.CODER, default = remoteOpenAiCompatible)
        }

        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertTrue(failure.error.message.contains("role=CODER"), failure.error.message)
    }

    // --- F. missing exact target -------------------------------------------

    @Test
    fun `F a missing saved connection fails explicitly instead of selecting another model`() {
        val registry = registry()
        runBlocking { registry.save(AgentRole.CODER, localFamily, devstral, "gone-connection") }
        // Every other connection is present, including a same-family remote one.
        val resolver = resolver(registry, connections(local, freeLlm, remoteOpenAiCompatible))

        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver.resolve(AgentRole.CODER, default = local)
        }

        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals("gone-connection", failure.error.details["connection"], "the saved identity is reported")
        assertEquals(AgentRole.CODER.name, failure.error.details["role"])
    }

    @Test
    fun `F a role whose provider is not connected fails explicitly`() {
        val registry = registry()
        runBlocking { registry.save(AgentRole.MAIN, AgentModelProviders.CEREBRAS, "cerebras-1") }
        val resolver = resolver(registry, connections(local, freeLlm))

        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver.resolve(AgentRole.MAIN, default = local)
        }

        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals(AgentModelProviders.CEREBRAS, failure.error.details["provider"])
        // Nothing was silently substituted: the configuration is intact.
        assertEquals(AgentModelProviders.CEREBRAS, registry.override(AgentRole.MAIN)?.providerId)
    }

    // --- G. adding remote connections moves nothing ------------------------

    @Test
    fun `G adding a Gemini and another OpenAI-compatible API connection never moves a local role`() {
        val registry = registry()
        val before = resolver(registry, connections(local)).resolve(AgentRole.MAIN, default = local)

        // The user now adds Gemini behind FreeLLMAPI and a remote OpenAI-compatible
        // endpoint, and the active model becomes Gemini.
        val gemini = connection("gemini", AgentModelProviders.GEMINI, AgentModelIds.GEMINI)
        val after = resolver(registry, connections(local, freeLlm, gemini, remoteOpenAiCompatible))

        listOf(AgentRole.MAIN, AgentRole.CODER, AgentRole.DEBUGGER).forEach { role ->
            val resolved = after.resolve(role, default = gemini)
            assertEquals(local.connectionId, resolved.connectionId, "$role must not move")
            assertEquals(devstral, resolved.model, "$role must not move")
        }
        assertEquals(before.connectionId, after.resolve(AgentRole.MAIN, default = gemini).connectionId)
    }

    // --- H. persistence ----------------------------------------------------

    @Test
    fun `H a saved assignment is reloaded with the exact connection and model`() {
        val store = InMemoryAgentRoleModelStore()
        runBlocking {
            registry(store).save(
                AgentRole.REVIEWER,
                AgentModelProviders.FREELMAPI,
                AgentModelIds.FREELLMAPI_GEMINI,
                freeLlm.connectionId,
            )
        }

        // A restart: a fresh registry over the same persisted store.
        val reopened = registry(store)
        runBlocking { reopened.load() }

        val selection = reopened.selection(AgentRole.REVIEWER)
        assertEquals(freeLlm.connectionId, selection.connectionId, "the connection identity survives the reload")
        assertEquals(AgentModelIds.FREELLMAPI_GEMINI, selection.model)
        assertEquals(AgentModelProviders.FREELMAPI, selection.providerId)
        assertTrue(selection.explicit)

        val resolved = resolver(reopened, connections(local, freeLlm)).resolve(AgentRole.REVIEWER, default = local)
        assertEquals(freeLlm.connectionId, resolved.connectionId)
        assertEquals(AgentModelIds.FREELLMAPI_GEMINI, resolved.model)
    }

    @Test
    fun `H an override is validated against its own domain after a reload`() {
        val store = InMemoryAgentRoleModelStore()
        runBlocking { registry(store).save(AgentRole.CODER, localFamily, devstral) }

        val reopened = registry(store)
        runBlocking { reopened.load() }

        // Only a remote connection of the same family exists after the restart.
        val resolver = resolver(reopened, connections(remoteOpenAiCompatible))
        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver.resolve(AgentRole.CODER, default = remoteOpenAiCompatible)
        }
        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals(localFamily, reopened.override(AgentRole.CODER)?.providerId)
    }

    // --- the built-in mapping itself ---------------------------------------

    @Test
    fun `every built-in role preference names its execution domain and a concrete model`() {
        AgentModelPreferences.DEFAULT.byRole.forEach { (role, preference) ->
            assertNotNull(preference.domain, "$role must name its execution domain, not a provider family alone")
            assertTrue(!preference.model.isNullOrBlank(), "$role must name a concrete model")
        }

        listOf(AgentRole.MAIN, AgentRole.CODER, AgentRole.DEBUGGER).forEach { role ->
            val preference = AgentModelPreferences.DEFAULT[role]
            assertEquals(ModelConnectionKind.LOCAL_CUSTOM, preference?.domain, "$role is a local role")
            assertEquals(localFamily, preference?.providerId)
            assertEquals(devstral, preference?.model)
        }

        listOf(AgentRole.REVIEWER, AgentRole.EXPLORER, AgentRole.RESEARCHER).forEach { role ->
            val preference = AgentModelPreferences.DEFAULT[role]
            assertEquals(ModelConnectionKind.API, preference?.domain, "$role is an API role")
            assertEquals(AgentModelProviders.FREELMAPI, preference?.providerId)
        }
    }
}
