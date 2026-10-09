package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.capability.CapabilityProvenance
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.manager.GatewayModelConnectionRegistry
import com.agentx.app.model.manager.ModelConnectionKind
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderType
import com.agentx.app.model.runtime.EndpointSource
import com.agentx.app.model.runtime.ModelEndpoint
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reported incident at the eligibility boundary, and the scope rule it turned on.
 *
 * A gateway connection serves many models, and Settings assigns a role a model
 * *independently* of the one the connection was saved with. The report was produced for
 * exactly that case:
 *
 * ```
 * MODEL_NOT_ELIGIBLE role=MAIN provider=freellmapi model=gemini-3.5-flash-lite
 * reason=UNKNOWN capability=toolCalling support=UNKNOWN provenance=DISCOVERED
 * ```
 *
 * The statement had been made and saved — for the model the connection was saved with.
 * Resolving a different model of the same connection both dropped that statement (which
 * is correct: it was never made about this model) and left nothing in its place, so the
 * selected model could never become eligible however many times the switch was set.
 *
 * These cases pin the rule from both sides: a statement reaches the exact model it was
 * made for, and never another. The configuration under test is built by the production
 * registry from a real preset, so what is exercised is the mapping the runtime actually
 * performs.
 */
class GatewayStatedModelCapabilityTest {

    private val providerId = "freellmapi"

    /** The gateway address the catalogue ships. These cases never contact it. */
    private val gatewayUrl = "https://agentx-vgtx.onrender.com/v1"

    /** The model the connection was saved with. */
    private val savedModel = "gemini-2.5-flash"

    /** The model Settings assigned to MAIN — the one in the report. */
    private val selectedModel = "gemini-3.5-flash-lite"

    /** A model of the same gateway the connection says nothing about. */
    private val silentModel = "glm-5.3"

    private val capabilities = InMemoryModelCapabilityRegistry(initial = emptyList())

    private val connections = GatewayModelConnectionRegistry(
        gateway = DefaultModelGateway(capabilities),
    )

    /** A saved gateway connection that states tool calling for [stated] and nothing else. */
    private fun preset(vararg stated: String): ModelPreset = stated.fold(
        ModelPreset(
            id = "gateway-preset",
            displayName = "FreeLLMAPI",
            providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
            modelIdentifier = savedModel,
            apiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
            apiBasePath = "/v1",
            endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, gatewayUrl),
            setupKind = ModelSetupKind.FREELLMAPI.id,
        ),
    ) { connection, model ->
        connection.stating(model, ModelCapabilityDeclaration.toolEnabledEndpoint())
    }

    /** The runtime configuration the connection flow produces for that preset. */
    private fun connect(vararg stated: String) = connections.connect(
        preset = preset(*stated),
        endpoint = ModelEndpoint(gatewayUrl, EndpointSource.CONFIGURED),
        credential = "test-key",
    )

    /** MAIN assigned to this connection and to [model], as Settings saves it. */
    private fun resolverFor(model: String, declaresToolCalling: Boolean = false) = AgentModelResolver(
        preferences = AgentModelPreferences().with(
            AgentRole.MAIN,
            RoleModelPreference(
                providerId = providerId,
                model = model,
                connectionId = "gateway-preset",
                explicit = true,
                declaresToolCalling = declaresToolCalling,
            ),
        ),
        connections = { connections.connections() },
        capabilityRegistry = capabilities,
    )

    private suspend fun resolve(
        model: String,
        config: com.agentx.app.model.ModelConfig,
        declaresToolCalling: Boolean = false,
    ) = resolverFor(model, declaresToolCalling).resolveForRole(AgentRole.MAIN, config)

    /**
     * The fix. The user stated tool calling for the very model MAIN runs, and the
     * statement now travels to it even though the connection is saved with another model
     * — the case that used to answer `UNKNOWN`/`DISCOVERED` for ever.
     */
    @Test
    fun `a statement made for the selected model reaches the eligibility check`() = runBlocking {
        val result = resolve(selectedModel, connect(savedModel, selectedModel))

        assertTrue(result.eligible, result.errorOrNull()?.message.orEmpty())
        assertEquals(selectedModel, result.config.model)
        // The selected model's own statement was applied, not the connection's.
        assertEquals(
            ModelCapabilityDeclaration.toolEnabledEndpoint(),
            result.config.declaredCapabilities,
        )
        assertEquals(CapabilitySupport.SUPPORTED, result.eligibility.profile.toolCalling)
        assertEquals(CapabilityProvenance.HARDCODED, result.eligibility.profile.provenance)
        assertTrue(result.eligibility.declared)
    }

    /**
     * The rejection itself, reproduced precisely, for a statement that exists but was made
     * for a different model of the connection.
     *
     * It stays a genuine rejection — nothing is known about this model, and unknown is not
     * support — and it can no longer happen *after* the user stated the capability for the
     * model they run. The verdict now names the models that were stated instead of leaving
     * the difference invisible.
     */
    @Test
    fun `a model the connection never stated is rejected, and the verdict says what was stated`() =
        runBlocking {
            val result = resolve(selectedModel, connect(savedModel))

            assertFalse(result.eligible)
            val error = result.errorOrNull()
            assertEquals(AgentErrorCode.MODEL_NOT_ELIGIBLE, error!!.code)
            assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
            assertEquals(CapabilitySupport.UNKNOWN, result.eligibility.profile.toolCalling)
            assertEquals(CapabilityProvenance.DISCOVERED, result.eligibility.profile.provenance)
            // No statement applied to this model...
            assertFalse(result.eligibility.declared)
            // ...and the verdict names the model the connection does state, so "nothing is
            // known" and "stated for another model" are distinguishable in a report.
            assertEquals(listOf(savedModel), result.eligibility.statedModels)
            assertEquals("false", error.details["declared"])
            assertEquals(savedModel, error.details["statedModels"])
            assertTrue(error.message.contains("declared=false"), error.message)
            assertTrue(error.message.contains("statedModels=$savedModel"), error.message)
        }

    /** A statement for one model is never applied to another, whatever the connection holds. */
    @Test
    fun `a statement for one model is not applied to another`() = runBlocking {
        val config = connect(savedModel, selectedModel)

        val other = resolve(silentModel, config)
        assertFalse(other.eligible)
        assertEquals(CapabilitySupport.UNKNOWN, other.eligibility.profile.toolCalling)
        assertEquals(ModelEligibilityState.UNKNOWN, other.eligibility.state)
        assertFalse(other.eligibility.declared)

        // The model that *was* stated still resolves, from that same configuration.
        val stated = resolve(selectedModel, config)
        assertTrue(stated.eligible, stated.errorOrNull()?.message.orEmpty())
        assertEquals(CapabilitySupport.SUPPORTED, stated.eligibility.profile.toolCalling)
    }

    /**
     * The ordinary case must not regress: a role on the model the connection itself names
     * keeps its statement, and a connection that states nothing resolves to unknown rather
     * than to a guess.
     */
    @Test
    fun `the connection's own model keeps its statement and an unstated one stays unknown`() =
        runBlocking {
            val own = resolve(savedModel, connect(savedModel))
            assertTrue(own.eligible, own.errorOrNull()?.message.orEmpty())
            assertEquals(CapabilitySupport.SUPPORTED, own.eligibility.profile.toolCalling)
            assertTrue(own.eligibility.declared)

            val undeclared = resolve(savedModel, connect())
            assertFalse(undeclared.eligible)
            assertEquals(CapabilitySupport.UNKNOWN, undeclared.eligibility.profile.toolCalling)
            assertEquals(CapabilityProvenance.DISCOVERED, undeclared.eligibility.profile.provenance)
            assertFalse(undeclared.eligibility.declared)
            assertTrue(undeclared.eligibility.statedModels.isEmpty())
        }

    /** A model the gateway is known not to serve tools for stays a definite rejection. */
    @Test
    fun `an explicitly unsupported statement is not turned into support`() = runBlocking {
        val unsupported = ModelCapabilityDeclaration.of(
            com.agentx.app.model.capability.ModelCapability.TOOL_CALLING,
            CapabilitySupport.UNSUPPORTED,
        )
        val config = connections.connect(
            preset = preset(savedModel).stating(selectedModel, unsupported),
            endpoint = ModelEndpoint(gatewayUrl, EndpointSource.CONFIGURED),
            credential = "test-key",
        )

        val result = resolve(selectedModel, config)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.CAPABILITY_UNSUPPORTED, result.eligibility.state)
        assertEquals(CapabilitySupport.UNSUPPORTED, result.eligibility.profile.toolCalling)
    }

    // --- Evidence B: the statement is made where the model is chosen ----------

    /**
     * The reported device failure: a FreeLLMAPI model selected as MAIN was rejected
     * even though the user had enabled the declaration switch.
     *
     * The switch lives on the connection and states a fact about the model the
     * connection is saved with, while Settings assigns a role any model the gateway's
     * catalog offers. When the two differ, nothing could state the capability for the
     * model the role actually runs — the hardcoded gateway entry (or, for an id it has
     * none for, the discovered one) answered instead, which is why the same rejection
     * appeared once as `provenance=HARDCODED` and once as `DISCOVERED`.
     *
     * The statement now belongs to the assignment, for the exact model it names.
     */
    @Test
    fun `an assignment that states tool calling makes the selected gateway model eligible`() =
        runBlocking {
            // The connection itself says nothing about the model the role runs.
            val result = resolve(selectedModel, connect(), declaresToolCalling = true)

            assertTrue(result.eligible, result.errorOrNull()?.message.orEmpty())
            assertEquals(selectedModel, result.config.model)
            assertEquals(
                ModelCapabilityDeclaration.toolEnabledEndpoint(),
                result.config.declaredCapabilities,
            )
            assertEquals(CapabilitySupport.SUPPORTED, result.eligibility.profile.toolCalling)
            assertTrue(result.eligibility.declared)
        }

    /**
     * The statement is scoped to the model the assignment names: it is never spread to
     * another model of the same connection, and never to another role.
     */
    @Test
    fun `an assignment's statement is not inherited by another model`() = runBlocking {
        val config = connect()

        val stated = resolve(selectedModel, config, declaresToolCalling = true)
        assertTrue(stated.eligible, stated.errorOrNull()?.message.orEmpty())

        val other = resolve(silentModel, config)
        assertFalse(other.eligible)
        assertEquals(CapabilitySupport.UNKNOWN, other.eligibility.profile.toolCalling)
        assertFalse(other.eligibility.declared)
    }

    /** The connection's own statement about a model outranks the assignment's. */
    @Test
    fun `the connection's own statement wins over the assignment's`() = runBlocking {
        val unsupported = ModelCapabilityDeclaration.of(
            com.agentx.app.model.capability.ModelCapability.TOOL_CALLING,
            CapabilitySupport.UNSUPPORTED,
        )
        val config = connections.connect(
            preset = preset(savedModel).stating(selectedModel, unsupported),
            endpoint = ModelEndpoint(gatewayUrl, EndpointSource.CONFIGURED),
            credential = "test-key",
        )

        val result = resolve(selectedModel, config, declaresToolCalling = true)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.CAPABILITY_UNSUPPORTED, result.eligibility.state)
        assertEquals(CapabilitySupport.UNSUPPORTED, result.eligibility.profile.toolCalling)
    }

    /**
     * The rejection as the device reports it, when nothing states the selected model:
     * unknown stays unknown, and the verdict says no statement applied.
     */
    @Test
    fun `an unstated gateway model is still rejected and reported as unstated`() = runBlocking {
        val result = resolve(selectedModel, connect())

        assertFalse(result.eligible)
        assertEquals(CapabilitySupport.UNKNOWN, result.eligibility.profile.toolCalling)
        assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
        assertFalse(result.eligibility.declared)
        assertEquals("false", result.errorOrNull()!!.details["declared"])
    }

    // --- Evidence A: the fresh-install default -------------------------------

    /**
     * A fresh install has no connection at all. MAIN's built-in preference is
     * domain-bound to the local runtime, which made resolution fail against a
     * connection the user had never created:
     *
     * ```
     * MODEL_NOT_CONNECTED role=MAIN provider=openai-compatible connection=openai-compatible
     * model=devstral-24b reason=CONNECTION_NOT_CONNECTED fallbackAvailable=false
     * ```
     *
     * The built-in default is a policy, not the user's assignment: it must report itself
     * as such, name no connection it does not have, and say what is actually missing.
     */
    @Test
    fun `a policy default with no matching connection reports itself as an unconfigured default`() =
        runBlocking {
            val apiActive = com.agentx.app.model.ModelConfig(
                providerId = providerId,
                baseUrl = gatewayUrl,
                model = selectedModel,
            ).copy(connectionKind = ModelConnectionKind.API)

            val result = AgentModelResolver(
                preferences = AgentModelPreferences.DEFAULT,
                connections = { emptyMap() },
                capabilityRegistry = capabilities,
            ).resolveForRole(AgentRole.MAIN, apiActive)

            assertFalse(result.eligible)
            val error = result.errorOrNull()!!
            assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, error.code)
            assertTrue(error.message.contains("reason=NO_CONNECTION_CONFIGURED"), error.message)
            assertTrue(error.message.contains("explicit=false"), error.message)
            // The default target is named as a default, and no connection is claimed.
            assertTrue(error.message.contains("defaultConnection=openai-compatible"), error.message)
            assertFalse(error.message.contains("connection=openai-compatible"), error.message)
            assertEquals("default", error.details["assignment"])
            assertEquals("false", error.details["explicit"])
            assertNull(error.details["connection"])
        }

    /**
     * The documented compatibility behaviour is preserved: a policy default whose
     * provider is absent still runs on the active model *of its own domain*.
     */
    @Test
    fun `a policy default falls back to the active model of its own domain`() = runBlocking {
        val groqActive = com.agentx.app.model.ModelConfig(
            providerId = "groq",
            baseUrl = "https://api.groq.com/openai/v1",
            model = "llama-3.3-70b-versatile",
        ).copy(connectionKind = ModelConnectionKind.API)

        val result = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { emptyMap() },
            capabilityRegistry = capabilities,
        ).resolveForRole(AgentRole.REVIEWER, groqActive)

        assertTrue(result.eligible, result.errorOrNull()?.message.orEmpty())
        assertEquals("groq", result.config.providerId)
    }

    /** An explicit assignment still fails hard: it is never answered by another model. */
    @Test
    fun `an explicit assignment is still never substituted`() = runBlocking {
        // A named connection that no longer exists fails rather than falling back.
        val missing = AgentModelResolver(
            preferences = AgentModelPreferences().with(
                AgentRole.MAIN,
                RoleModelPreference(providerId, selectedModel, connectionId = "deleted-preset", explicit = true),
            ),
            connections = { emptyMap() },
            capabilityRegistry = capabilities,
        ).resolveForRole(
            AgentRole.MAIN,
            com.agentx.app.model.ModelConfig(providerId, gatewayUrl, selectedModel),
        )
        assertFalse(missing.eligible)
        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, missing.errorOrNull()!!.code)
        assertEquals("deleted-preset", missing.errorOrNull()!!.details["connection"])
        assertEquals("true", missing.errorOrNull()!!.details["explicit"])
    }
}
