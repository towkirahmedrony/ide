package com.agentx.app.agent.model

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.testConfig
import com.agentx.app.agent.testDomain
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.ModelCapability
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The explicit role → execution domain → connection identity → model routing.
 *
 * Every case asserts a concrete model *id*, not a provider family: a local role is
 * pinned to the local `openai-compatible` connection and its Devstral model, and an
 * API role to the FreeLLMAPI identity and the exact Gemini/GPT-OSS model it targets.
 * The resolver is pure, so this is exactly what a running agent would receive.
 */
class ApiRoleRoutingTest {

    private val active = testConfig()

    private fun connection(
        providerId: String,
        model: String,
        connectionId: String = providerId,
    ) = ModelConfig(
        providerId = providerId,
        baseUrl = "https://$connectionId.example/v1",
        model = model,
        connectionId = connectionId,
        connectionKind = testDomain(providerId),
    )

    private val local = connection(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "devstral-24b")
    private val freeLlm = connection(AgentModelProviders.FREELMAPI, AgentModelIds.FREELLMAPI_GEMINI)
    private val gemini = connection(AgentModelProviders.GEMINI, "gemini-3.5-flash")
    private val groq = connection(AgentModelProviders.GROQ, "llama-3.3-70b-versatile")

    private fun resolver(vararg connections: ModelConfig) = AgentModelResolver(
        preferences = AgentModelPreferences.DEFAULT,
        connections = { connections.associateBy { it.connectionId } },
    )

    private val localRoles = listOf(AgentCatalog.MAIN, AgentCatalog.CODER, AgentCatalog.DEBUGGER)

    // --- the target mapping, by literal id ---------------------------------

    @Test
    fun `local roles resolve to the local devstral connection`() {
        val live = resolver(local, freeLlm, gemini, groq)
        localRoles.forEach { definition ->
            val resolved = live.resolve(definition, active)
            assertEquals("devstral-24b", resolved.model, definition.name)
            assertEquals(AgentModelProviders.OPENAI_COMPATIBLE, resolved.providerId, definition.name)
            assertEquals(local.connectionId, resolved.connectionId, definition.name)
        }
    }

    @Test
    fun `api roles resolve to the explicit FreeLLMAPI models`() {
        val live = resolver(local, freeLlm, gemini, groq)

        val reviewer = live.resolve(AgentCatalog.REVIEWER, active)
        assertEquals("gemini-2.5-flash", reviewer.model)
        assertEquals(AgentModelProviders.FREELMAPI, reviewer.providerId)
        assertEquals(freeLlm.connectionId, reviewer.connectionId)

        val explorer = live.resolve(AgentCatalog.EXPLORER, active)
        assertEquals("openai/gpt-oss-20b", explorer.model)
        assertEquals(AgentModelProviders.FREELMAPI, explorer.providerId)
        assertEquals(freeLlm.connectionId, explorer.connectionId)
    }

    @Test
    fun `the catalog declares the literal model each role targets`() {
        assertEquals("devstral-24b", AgentCatalog.MAIN.modelPreference)
        assertEquals("devstral-24b", AgentCatalog.CODER.modelPreference)
        assertEquals("devstral-24b", AgentCatalog.DEBUGGER.modelPreference)
        assertEquals("gemini-2.5-flash", AgentCatalog.REVIEWER.modelPreference)
        assertEquals("openai/gpt-oss-20b", AgentCatalog.EXPLORER.modelPreference)
    }

    // --- isolation ----------------------------------------------------------

    @Test
    fun `adding or removing api providers never moves a local role`() {
        val baseline = resolver(local).resolve(AgentCatalog.MAIN, active)
        listOf(
            resolver(local, freeLlm),
            resolver(local, gemini),
            resolver(local, groq),
            resolver(local, freeLlm, gemini, groq),
        ).forEach { live ->
            localRoles.forEach { definition ->
                val resolved = live.resolve(definition, active)
                assertEquals(baseline.model, resolved.model, definition.name)
                assertEquals(baseline.providerId, resolved.providerId, definition.name)
                assertEquals(baseline.connectionId, resolved.connectionId, definition.name)
            }
        }
        // Removing the API connection leaves the local roles exactly where they were.
        assertEquals(baseline, resolver(local).resolve(AgentCatalog.MAIN, active))
    }

    @Test
    fun `local and api execution domains cannot cross`() {
        // API-only: a local role fails rather than running on a remote model.
        val apiOnly = resolver(freeLlm, gemini, groq)
        localRoles.forEach { definition ->
            assertFailsWith<AgentModelResolutionException>(definition.name) {
                apiOnly.resolve(definition, active)
            }
        }
        // Local-only: an API role fails rather than running on the local model.
        val localOnly = resolver(local)
        listOf(AgentCatalog.REVIEWER, AgentCatalog.EXPLORER).forEach { definition ->
            assertFailsWith<AgentModelResolutionException>(definition.name) {
                localOnly.resolve(definition, active)
            }
        }
    }

    // --- connection identity, not connection order -------------------------

    @Test
    fun `two connections of one family do not resolve by connection order`() {
        // Two user-run OpenAI-compatible endpoints. MAIN is bound to the local domain
        // and names no connection, so there is no correct answer to guess: choosing the
        // first would make routing depend on the order the connections are listed in.
        val devstral = connection(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "devstral-24b", connectionId = "local-devstral")
        val other = connection(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "qwen2.5-coder-14b", connectionId = "local-qwen")

        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver(devstral, other).resolve(AgentCatalog.MAIN, active)
        }

        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals("CONNECTION_AMBIGUOUS", failure.error.details["reason"])
        val candidates = assertNotNull(failure.error.details["candidates"]).split(",").toSet()
        assertEquals(setOf("local-devstral", "local-qwen"), candidates)
    }

    @Test
    fun `a connection that is still unique still resolves`() {
        // The guard is only about ambiguity: one connection of the required identity
        // keeps resolving exactly as before.
        val devstral = connection(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "devstral-24b", connectionId = "local-devstral")
        val freeLlmAlt = connection(AgentModelProviders.FREELMAPI, "gemini-2.5-flash", connectionId = "gateway")

        val resolved = resolver(devstral, freeLlmAlt).resolve(AgentCatalog.MAIN, active)
        assertEquals("local-devstral", resolved.connectionId)
        assertEquals("devstral-24b", resolved.model)
    }

    @Test
    fun `a role bound to a named connection ignores the others`() {
        val devstral = connection(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "devstral-24b", connectionId = "local-devstral")
        val other = connection(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, "qwen2.5-coder-14b", connectionId = "local-qwen")
        val preferences = AgentModelPreferences.DEFAULT.with(
            AgentRole.MAIN,
            RoleModelPreference(
                providerId = AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
                model = "devstral-24b",
                connectionId = "local-devstral",
            ),
        )

        val resolved = AgentModelResolver(
            preferences = preferences,
            connections = { mapOf(devstral.connectionId to devstral, other.connectionId to other) },
        ).resolve(AgentCatalog.MAIN, active)

        assertEquals("local-devstral", resolved.connectionId)
        assertEquals("devstral-24b", resolved.model)
    }

    // --- missing connection / unavailable model ----------------------------

    @Test
    fun `an api role fails clearly when the FreeLLMAPI connection is absent`() {
        // The local model and two other API providers are connected. The Reviewer is
        // still bound to the API domain and the FreeLLMAPI identity, so it fails
        // instead of silently reviewing on Devstral or on another API provider.
        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver(local, gemini, groq).resolve(AgentCatalog.REVIEWER, active)
        }

        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals("CONNECTION_NOT_CONNECTED", failure.error.details["reason"])
        assertEquals(AgentModelProviders.FREELMAPI, failure.error.details["provider"])
        assertEquals("gemini-2.5-flash", failure.error.details["model"])
    }

    @Test
    fun `an explorer fails the same way when only the local connection exists`() {
        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver(local).resolve(AgentCatalog.EXPLORER, active)
        }
        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals("openai/gpt-oss-20b", failure.error.details["model"])
    }

    @Test
    fun `a connected gateway that does not offer the assigned model is reported, not substituted`() {
        // FreeLLMAPI is connected, but its catalog no longer lists the Reviewer's model.
        // The existing evaluation reports the model as unavailable; nothing replaces it
        // with another of the gateway's models and nothing moves the role to Devstral.
        val option = ProviderModelOption(
            providerId = AgentModelProviders.FREELMAPI,
            providerLabel = RoleModelEvaluation.providerLabel(AgentModelProviders.FREELMAPI),
            models = listOf("llama-3.3-70b-versatile"),
            connected = true,
            connectionId = "gateway-1",
        )

        val status = RoleModelEvaluation.evaluate(
            selection = RoleModelSelection(
                role = AgentRole.REVIEWER,
                providerId = AgentModelProviders.FREELMAPI,
                model = "gemini-2.5-flash",
                connectionId = null,
                explicit = false,
            ),
            options = listOf(option),
            availableConnections = setOf("gateway-1"),
        )

        assertEquals(RoleModelState.MODEL_UNAVAILABLE, status.state)
        assertEquals("gemini-2.5-flash", status.model)
        assertEquals(AgentModelProviders.FREELMAPI, status.providerId)
    }

    // --- eligibility --------------------------------------------------------

    @Test
    fun `the api roles are eligible on their routed models under the unchanged capability rules`() = runBlocking {
        val live = resolver(local, freeLlm, groq)

        val reviewer = live.resolveForRole(AgentRole.REVIEWER, active)
        val explorer = live.resolveForRole(AgentRole.EXPLORER, active)

        assertTrue(reviewer.eligible, reviewer.errorOrNull()?.message ?: "reviewer not eligible")
        assertTrue(explorer.eligible, explorer.errorOrNull()?.message ?: "explorer not eligible")
        assertEquals("gemini-2.5-flash", reviewer.config.model)
        assertEquals("openai/gpt-oss-20b", explorer.config.model)
        // Tool calling is still required and still checked; the two routed models
        // satisfy it because they are declared per model id, not because the gateway
        // speaks the OpenAI-compatible protocol.
        assertTrue(reviewer.eligibility.profile.supports(ModelCapability.TOOL_CALLING))
        assertTrue(explorer.eligibility.profile.supports(ModelCapability.TOOL_CALLING))
        assertTrue(reviewer.eligibility.profile.supports(ModelCapability.STREAMING))
        assertTrue(explorer.eligibility.profile.supports(ModelCapability.STREAMING))
    }

    @Test
    fun `an undeclared gateway model is not assumed tool capable`() = runBlocking {
        // A model only the gateway lists is UNKNOWN for tool calling, so it cannot
        // fill an agent role: nothing is claimed from the wire protocol.
        val unknown = connection(AgentModelProviders.FREELMAPI, "some-new-gateway-model")
        val preferences = AgentModelPreferences.DEFAULT.with(
            AgentRole.EXPLORER,
            RoleModelPreference(AgentModelProviders.FREELMAPI, "some-new-gateway-model"),
        )
        val result = AgentModelResolver(
            preferences = preferences,
            connections = { mapOf(unknown.connectionId to unknown) },
        ).resolveForRole(AgentRole.EXPLORER, active)

        assertTrue(!result.eligible)
        assertEquals("some-new-gateway-model", result.config.model)
    }
}
