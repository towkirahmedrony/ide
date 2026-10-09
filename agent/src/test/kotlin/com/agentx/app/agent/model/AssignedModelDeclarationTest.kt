package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.capability.statedCapabilitiesFor
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.manager.GatewayModelConnectionRegistry
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reported failure on the role → model path, end to end as the application drives it.
 *
 * A fresh install could not run MAIN on a FreeLLMAPI model. The runtime diagnostic named
 * the capability verdict and, decisively, that no statement had reached it:
 *
 * ```
 * MODEL_NOT_ELIGIBLE role=MAIN provider=freellmapi model=gemini-3.5-flash-lite
 * reason=UNKNOWN capability=toolCalling support=UNKNOWN provenance=DISCOVERED declared=false
 * ```
 *
 * A gateway's own catalogue cannot describe tool calling, so the *user's* statement is the
 * only thing that can resolve it — and the statement lives on the role's assignment, which
 * is where the model is chosen. That statement had no producer: Settings → Agent Models
 * saved the assignment without it, and even a saved one was dropped by the resolver
 * whenever the active connection was the connection the role was assigned from. So a
 * declared assignment could never become eligible, and `declared=false` was permanent.
 *
 * These cases drive the production path — the real [AgentRoleModelRegistry] over a store,
 * the real [AgentModelResolver] built the way `AgentModule` builds it, and a connection
 * built by the real [GatewayModelConnectionRegistry] from a real preset — so what is
 * asserted is what a running agent receives.
 */
class AssignedModelDeclarationTest {

    private val providerId = "freellmapi"

    /** The gateway address the catalogue ships. These cases never contact it. */
    private val gatewayUrl = "https://agentx-vgtx.onrender.com/v1"

    /** The model the connection was saved with. */
    private val savedModel = "gemini-2.5-flash"

    /** The model Settings assigned to MAIN — the one in the report. */
    private val assignedModel = "gemini-3.5-flash-lite"

    /** Another model of the same gateway. */
    private val otherModel = "glm-5.3"

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

    /** The runtime configuration a saved connection produces. */
    private fun connect(vararg stated: String): ModelConfig = connections.connect(
        preset = preset(*stated),
        endpoint = ModelEndpoint(gatewayUrl, EndpointSource.CONFIGURED),
        credential = "test-key",
    )

    private fun registry(store: AgentRoleModelStore) =
        AgentRoleModelRegistry(DefaultAgentRoleModelRepository(store))

    /** The resolver `AgentModule` builds: built-in defaults, live connections, live prefs. */
    private fun resolver(
        registry: AgentRoleModelRegistry,
        connection: ModelConfig? = null,
    ): AgentModelResolver {
        val live: Map<String, ModelConfig> =
            if (connection == null) emptyMap() else mapOf(connection.connectionId to connection)
        return AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { live },
            livePreferences = { registry.preferences() },
            capabilityRegistry = capabilities,
        )
    }

    /** The active configuration the session happens to be running on. */
    private val active = ModelConfig(
        providerId = "openai-compatible",
        baseUrl = "http://127.0.0.1:8080/v1",
        model = "devstral-24b",
    )

    // --- the producer: save, reload, run ------------------------------------

    @Test
    fun `stating tool calling for the assigned model, saving and reloading makes MAIN eligible`() = runBlocking {
        val store = InMemoryAgentRoleModelStore()
        // Settings → Agent Models: the role, the model the user picked from the gateway's
        // catalogue, and the statement the user made for that model.
        registry(store).save(
            role = AgentRole.MAIN,
            providerId = providerId,
            model = assignedModel,
            connectionId = "gateway-preset",
            declaresToolCalling = true,
        )

        // A restart: the assignment is reloaded from the store.
        val registry = registry(store)
        registry.load()
        assertEquals(true, registry.override(AgentRole.MAIN)?.declaresToolCalling)
        assertEquals(true, registry.selection(AgentRole.MAIN).declaresToolCalling)

        val result = resolver(registry, connect()).resolveForRole(AgentRole.MAIN, active)

        assertTrue(result.eligible, result.errorOrNull()?.message.orEmpty())
        assertEquals(assignedModel, result.config.model)
        assertEquals(providerId, result.config.providerId)
        assertEquals(CapabilitySupport.SUPPORTED, result.eligibility.profile.toolCalling)
        assertTrue(result.eligibility.declared)
        // Stated for one model only: the connection's own model is not dragged along.
        assertEquals(
            ModelCapabilityDeclaration.toolEnabledEndpoint(),
            result.config.statedCapabilitiesFor(assignedModel),
        )
        assertNull(result.config.statedCapabilitiesFor(savedModel))
    }

    @Test
    fun `the same assignment without the statement stays unknown and is rejected`() = runBlocking {
        val store = InMemoryAgentRoleModelStore()
        registry(store).save(AgentRole.MAIN, providerId, assignedModel, connectionId = "gateway-preset")

        val registry = registry(store)
        registry.load()
        assertFalse(registry.selection(AgentRole.MAIN).declaresToolCalling)

        val result = resolver(registry, connect()).resolveForRole(AgentRole.MAIN, active)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
        assertEquals(CapabilitySupport.UNKNOWN, result.eligibility.profile.toolCalling)
        assertFalse(result.eligibility.declared)
        assertEquals(AgentErrorCode.MODEL_NOT_ELIGIBLE, result.errorOrNull()!!.code)
    }

    // --- the consumer: every branch that resolves the assignment ------------

    @Test
    fun `the active-connection branch carries the assignment's statement too`() = runBlocking {
        // The role was assigned from the gateway catalogue without naming the connection —
        // Settings may save either shape — so the resolver takes the branch that re-points
        // the *active* connection at the role's model.
        val store = InMemoryAgentRoleModelStore()
        registry(store).save(
            role = AgentRole.MAIN,
            providerId = providerId,
            model = assignedModel,
            connectionId = null,
            declaresToolCalling = true,
        )
        val registry = registry(store)
        registry.load()

        val gateway = connect()
        val result = resolver(registry).resolveForRole(AgentRole.MAIN, default = gateway)

        assertTrue(result.eligible, result.errorOrNull()?.message.orEmpty())
        assertEquals(assignedModel, result.config.model)
        assertTrue(result.eligibility.declared)
        assertEquals(CapabilitySupport.SUPPORTED, result.eligibility.profile.toolCalling)
    }

    @Test
    fun `a statement is never inherited by a different model of the same connection`() = runBlocking {
        // The connection itself states tool calling for another model of the same gateway.
        val gateway = connect(otherModel)

        val store = InMemoryAgentRoleModelStore()
        registry(store).save(AgentRole.MAIN, providerId, assignedModel, connectionId = "gateway-preset")
        val registry = registry(store)
        registry.load()

        val result = resolver(registry, gateway).resolveForRole(AgentRole.MAIN, active)

        assertFalse(result.eligible)
        assertEquals(CapabilitySupport.UNKNOWN, result.eligibility.profile.toolCalling)
        assertFalse(result.eligibility.declared)
        // The verdict still says which models *were* stated, so the difference between
        // "nothing stated" and "stated for another model" is not invisible.
        assertEquals(listOf(otherModel), result.eligibility.statedModels)
    }

    @Test
    fun `switching the assigned model does not carry the previous statement over`() = runBlocking {
        val store = InMemoryAgentRoleModelStore()
        val first = registry(store)
        first.save(AgentRole.MAIN, providerId, otherModel, connectionId = "gateway-preset", declaresToolCalling = true)

        // The user switches the assignment to another model of the same connection.
        val second = registry(store)
        second.load()
        second.save(AgentRole.MAIN, providerId, assignedModel, connectionId = "gateway-preset", declaresToolCalling = false)
        assertEquals(false, second.override(AgentRole.MAIN)?.declaresToolCalling)

        // A third registry, as after a restart, sees only the current assignment.
        val reloaded = registry(store)
        reloaded.load()
        val result = resolver(reloaded, connect()).resolveForRole(AgentRole.MAIN, active)

        assertEquals(assignedModel, result.config.model)
        assertFalse(result.eligibility.declared)
        assertEquals(CapabilitySupport.UNKNOWN, result.eligibility.profile.toolCalling)
        assertFalse(result.eligible)
    }

    // --- the states must stay distinguishable ------------------------------

    @Test
    fun `no connection at all is reported as unconfigured, never as an eligibility verdict`() = runBlocking {
        val registry = registry(InMemoryAgentRoleModelStore())
        registry.load()

        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver(registry).resolveForRole(AgentRole.MAIN, active)
        }

        // The built-in default with nothing connected: a missing connection, and the same
        // state Settings reports. Not an eligibility verdict about a model that is here.
        assertEquals(AgentErrorCode.NOT_CONFIGURED, failure.error.code)
        assertEquals("NO_CONNECTION_CONFIGURED", failure.error.details["reason"])
        assertFalse(failure.error.message.contains("MODEL_NOT_ELIGIBLE"), failure.error.message)
    }

    @Test
    fun `an ineligible model is reported as an eligibility verdict, never as no model connected`() =
        runBlocking {
            val store = InMemoryAgentRoleModelStore()
            registry(store).save(AgentRole.MAIN, providerId, assignedModel, connectionId = "gateway-preset")
            val registry = registry(store)
            registry.load()

            val result = resolver(registry, connect()).resolveForRole(AgentRole.MAIN, active)
            val error = result.errorOrNull()!!

            assertEquals(AgentErrorCode.MODEL_NOT_ELIGIBLE, error.code)
            // The model is connected and ineligible — the message must say so, and must
            // never read as the missing-connection state.
            assertFalse(error.message.contains("No model is online"), error.message)
            assertFalse(error.message.contains("not connected"), error.message)
            // It distinguishes an unstated model from a model the connection states
            // something about, and says where to fix it.
            assertTrue(error.message.contains("Nothing has been stated about tool calling for $assignedModel"), error.message)
            assertTrue(error.message.contains("Settings → Agent Models"), error.message)
            assertTrue(error.message.contains("MODEL_NOT_ELIGIBLE role=MAIN provider=$providerId model=$assignedModel"), error.message)

            // And the same verdict for a connection that states something for another model
            // reads differently, naming what was stated.
            val sibling = resolver(registry, connect(otherModel)).resolveForRole(AgentRole.MAIN, active)
            val siblingError = sibling.errorOrNull()!!
            assertTrue(
                siblingError.message.contains("The statement saved for this connection covers $otherModel"),
                siblingError.message,
            )
            assertFalse(siblingError.message.contains("No model is online"), siblingError.message)
        }

    // --- Settings and the runtime must agree -------------------------------

    @Test
    fun `settings judges a declared assignment exactly as the runtime does`() = runBlocking {
        val store = InMemoryAgentRoleModelStore()
        registry(store).save(
            role = AgentRole.MAIN,
            providerId = providerId,
            model = assignedModel,
            connectionId = "gateway-preset",
            declaresToolCalling = true,
        )
        val registry = registry(store)
        registry.load()

        val option = ProviderModelOption(
            providerId = providerId,
            providerLabel = "FreeLLMAPI",
            models = listOf(savedModel, assignedModel),
            connected = true,
            connectionId = "gateway-preset",
            endpoint = gatewayUrl,
        )

        // Declared: the screen must not call it unconfirmed while the runtime runs it.
        val declared = RoleModelEvaluation.evaluate(
            selection = registry.selection(AgentRole.MAIN),
            options = listOf(option),
            availableConnections = setOf("gateway-preset"),
            capabilities = capabilities,
        )
        assertEquals(RoleModelState.CONNECTED, declared.state)
        assertTrue(declared.runtimeReady)

        // Not declared: the same screen must report the unresolved verdict, and neither
        // state may be reported as NOT_CONFIGURED — the connection is right there.
        val undeclared = RoleModelEvaluation.evaluate(
            selection = registry.selection(AgentRole.MAIN).copy(declaresToolCalling = false),
            options = listOf(option),
            availableConnections = setOf("gateway-preset"),
            capabilities = capabilities,
        )
        assertEquals(RoleModelState.CAPABILITY_UNKNOWN, undeclared.state)
        assertFalse(undeclared.runtimeReady)
    }

    @Test
    fun `only the providers whose models the user states themselves offer the statement`() {
        // A custom/local endpoint and the endpoint-addressed gateway: nothing authoritative
        // describes their models, so the user's statement is the one that counts.
        assertTrue(RoleModelEvaluation.declarableByUser(AgentModelProviders.OPENAI_COMPATIBLE))
        assertTrue(RoleModelEvaluation.declarableByUser(AgentModelProviders.FREELMAPI))
        // Catalogue providers state their own capabilities; a switch must not override them.
        assertFalse(RoleModelEvaluation.declarableByUser(AgentModelProviders.GEMINI))
        assertFalse(RoleModelEvaluation.declarableByUser(AgentModelProviders.GROQ))
    }
}
