package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.testDomain
import com.agentx.app.core.errorOrNull
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.connect.ModelSetupKind
import com.agentx.app.model.manager.ModelManagers
import com.agentx.app.model.preset.EndpointConfig
import com.agentx.app.model.preset.EndpointDiscoveryMode
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A role's saved assignment records the user's choice, and the choice made in
 * Settings → Agent Models is a **provider family plus a model** — not one of that
 * family's connections.
 *
 * The regression these tests lock down: a role assigned from Settings used to be
 * pinned to a connection of the family that the editor picked on its own. When the
 * user later deleted that connection and created a replacement for the same provider
 * and model (a fresh preset id), the saved assignment still named the old, gone
 * connection, and every run of the role failed with:
 *
 * ```
 * MODEL_NOT_CONNECTED role=MAIN provider=freellmapi connection=<old id>
 * model=qwen-3.8-27b reason=CONNECTION_NOT_CONNECTED fallbackAvailable=false
 * explicit=true assignment=user
 * ```
 *
 * Connection lifecycles (replace, disconnect, delete) are covered too. The intended
 * semantics — established by `RoleModelRuntimeConsistencyTest` and the
 * `AgentRoleModelStatus.CONNECTION_MISSING` contract — is that an assignment which
 * *names* a connection is authoritative and, when that connection is gone, is
 * preserved and reported rather than silently re-pointed at a sibling.
 *
 * These tests drive the real [AgentRoleModelRegistry] and [AgentModelResolver]; no
 * network request is made and the resolver is pure, so the assertions describe
 * exactly what a running agent would receive.
 */
class StaleConnectionAssignmentTest {

    /** The exact identities from the reported failure. */
    private val oldConnection = "d4c5a26a-6d86-47cc-840c-fad0a70c5ca3"
    private val newConnection = "00425aeb-48be-4df3-8a30-554e88b46821"
    private val model = "qwen-3.8-27b"
    private val provider = AgentModelProviders.FREELMAPI

    private fun gateway(connectionId: String, modelId: String = model) = ModelConfig(
        providerId = provider,
        baseUrl = "https://$connectionId.example/v1",
        model = modelId,
        connectionId = connectionId,
        connectionKind = testDomain(provider),
    )

    /** A local model, to prove an API assignment is never answered by the active model. */
    private fun local(modelId: String = AgentModelIds.DEVSTRAL_24B) = ModelConfig(
        providerId = AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
        baseUrl = "http://127.0.0.1:11434/v1",
        model = modelId,
        connectionId = "local-devstral",
        connectionKind = testDomain(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL),
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

    // --- the reported scenario ---------------------------------------------

    @Test
    fun `a replacement connection for the same provider and model is followed, not refused`() = runBlocking {
        val store = InMemoryAgentRoleModelStore()
        val registry = registry(store)
        // Exactly what the Settings editor now saves: the provider family and the
        // model the user picked, and no connection identity the user never chose.
        registry.save(AgentRole.MAIN, provider, model)
        assertNull(
            store.loadAll().single { it.role == AgentRole.MAIN }.connectionId,
            "the assignment records no connection instance",
        )

        // While the original connection is up, the role runs on it.
        val original = gateway(oldConnection)
        val before = resolver(registry, connections(original)).resolve(AgentRole.MAIN, default = original)
        assertEquals(oldConnection, before.connectionId)
        assertEquals(model, before.model)

        // The user deletes that connection and creates a replacement for the same
        // provider and model — a fresh preset id. Before the fix this refused with
        // MODEL_NOT_CONNECTED; the replacement now serves the assignment.
        val replacement = gateway(newConnection)
        val after = resolver(registry, connections(replacement)).resolve(AgentRole.MAIN, default = replacement)
        assertEquals(newConnection, after.connectionId, "the replacement serves the assigned provider and model")
        assertEquals(provider, after.providerId)
        assertEquals(model, after.model)

        // Nothing was re-pinned behind the user's back: the saved assignment is still
        // provider+model scoped, so the next replacement is followed the same way.
        assertNull(store.loadAll().single { it.role == AgentRole.MAIN }.connectionId)
    }

    @Test
    fun `a family-scoped assignment is never answered by the active model of another provider`() = runBlocking {
        val registry = registry()
        registry.save(AgentRole.MAIN, provider, model)

        // The active configuration is the local model: it must not stand in for the API
        // assignment just because it is what the session happens to be running.
        val localModel = local()
        val resolved = resolver(registry, connections(localModel, gateway(newConnection)))
            .resolve(AgentRole.MAIN, default = localModel)

        assertEquals(newConnection, resolved.connectionId)
        assertEquals(provider, resolved.providerId)
    }

    @Test
    fun `two same-domain connections are never guessed when the assignment names none`() = runBlocking {
        val registry = registry()
        registry.save(AgentRole.MAIN, provider, model)

        val a = gateway("gateway-a")
        val b = gateway("gateway-b")
        // Neither is the active model, so nothing identifies which connection the
        // family-scoped assignment means: choosing one would be list order, which must
        // fail instead of running the role on a guessed connection.
        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver(registry, connections(a, b)).resolve(AgentRole.MAIN, default = local())
        }

        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals("CONNECTION_AMBIGUOUS", failure.error.details["reason"])
        assertEquals("gateway-a,gateway-b", failure.error.details["candidates"])
    }

    // --- an explicit (pinned) assignment -----------------------------------

    @Test
    fun `an explicit assignment whose connection is gone fails and keeps its stored identity`() = runBlocking {
        val store = InMemoryAgentRoleModelStore()
        val registry = registry(store)
        // A role bound to one exact connection: this binding is the user's and it is
        // authoritative.
        registry.save(AgentRole.MAIN, provider, model, oldConnection)

        val replacement = gateway(newConnection)
        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver(registry, connections(replacement)).resolve(AgentRole.MAIN, default = replacement)
        }

        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals(oldConnection, failure.error.details["connection"], "the stored connection is reported")
        assertEquals(model, failure.error.details["model"])
        assertEquals("user", failure.error.details["assignment"])
        assertEquals("false", failure.error.details["fallbackAvailable"])
        assertTrue(
            failure.error.message.contains("reason=CONNECTION_NOT_CONNECTED"),
            "the reason must be visible in the log line: ${failure.error.message}",
        )

        // The pin is preserved exactly as saved: the replacement is not substituted.
        assertEquals(oldConnection, store.loadAll().single { it.role == AgentRole.MAIN }.connectionId)

        // Settings reaches the same verdict from the same inputs.
        val status = RoleModelEvaluation.evaluate(
            selection = registry.selection(AgentRole.MAIN),
            options = emptyList(),
            availableConnections = setOf(newConnection),
        )
        assertEquals(RoleModelState.CONNECTION_MISSING, status.state)
        assertEquals(oldConnection, status.connectionId)
        assertTrue(!status.runtimeReady)
    }

    @Test
    fun `a valid explicit assignment still selects its intended connection`() = runBlocking {
        val registry = registry()
        registry.save(AgentRole.MAIN, provider, model, oldConnection)

        // Two connections of the family expose the *same* model; only the connection
        // identity can tell them apart, and the saved one wins.
        val intended = gateway(oldConnection)
        val sibling = gateway(newConnection)
        val resolved = resolver(registry, connections(intended, sibling))
            .resolve(AgentRole.MAIN, default = sibling)

        assertEquals(oldConnection, resolved.connectionId, "the saved connection is honored")
        assertEquals(model, resolved.model)
    }

    // --- deleting a connection that an assignment names ---------------------

    @Test
    fun `deleting the connection an assignment names never re-points the assignment`() = runBlocking {
        val manager = ModelManagers.create(
            presetStore = InMemoryModelPresetStore(listOf(freeLlmPreset(oldConnection))),
            monitorEnabled = false,
            ioDispatcher = Dispatchers.Unconfined,
        )
        val store = InMemoryAgentRoleModelStore()
        val registry = registry(store)
        registry.save(AgentRole.MAIN, provider, model, oldConnection)

        // The user deletes the model preset the assignment is bound to. The model layer
        // releases that connection; it must touch nothing about the role mapping.
        assertTrue(
            manager.deletePreset(oldConnection).errorOrNull() == null,
            "the preset delete must succeed",
        )
        assertNull(manager.preset(oldConnection), "the preset is gone")
        assertEquals(
            oldConnection,
            store.loadAll().single { it.role == AgentRole.MAIN }.connectionId,
            "the assignment is untouched",
        )

        // A replacement for the same provider and model is connected afterwards. The
        // pinned assignment is reported stale, never silently answered by it.
        val replacement = gateway(newConnection)
        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver(registry, connections(replacement)).resolve(AgentRole.MAIN, default = replacement)
        }
        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals(oldConnection, failure.error.details["connection"])
    }

    /** A saved FreeLLMAPI preset, so the delete path has a real preset to remove. */
    private fun freeLlmPreset(id: String, modelId: String = model) = ModelPreset(
        id = id,
        displayName = "FreeLLMAPI",
        providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        modelIdentifier = modelId,
        apiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
        apiBasePath = "/v1",
        endpoint = EndpointConfig(EndpointDiscoveryMode.CONFIGURED_ENDPOINT, "https://gateway.example"),
        setupKind = ModelSetupKind.FREELLMAPI.id,
    )
}
