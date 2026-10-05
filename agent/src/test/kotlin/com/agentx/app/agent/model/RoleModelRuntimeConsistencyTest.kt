package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapabilityProfile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Settings and the runtime must reach the same verdict about the same assignment.
 *
 * Settings used to judge a role by matching the provider *family* and then reporting
 * `CONNECTED`, while the runtime resolved the exact saved `connectionId` and could
 * refuse with `MODEL_NOT_CONNECTED`. Two algorithms, two answers, and the user saw
 * "Connected" for something that would not run.
 *
 * These tests exercise the shared evaluation against the runtime's own checker and
 * resolver, so the two cannot drift apart unnoticed: wherever a state is asserted, the
 * runtime's answer for the same inputs is asserted alongside it.
 */
class RoleModelRuntimeConsistencyTest {

    private val provider = "openai-compatible"

    /** The model id both connections expose, per the task's collision case. */
    private val shared = "qwen2.5-coder-14b"

    private fun profile(
        modelId: String = shared,
        support: CapabilitySupport = CapabilitySupport.SUPPORTED,
        enabled: Boolean = true,
        known: Boolean = true,
    ) = ModelCapabilityProfile(
        providerId = provider,
        modelId = modelId,
        displayName = modelId,
        toolCalling = support,
        streaming = support,
        vision = support,
        structuredOutput = support,
        reasoning = support,
        enabled = enabled,
        known = known,
    )

    /**
     * A registry containing only the profiles this test states.
     *
     * The default constructor pre-loads every built-in definition, which would silently
     * win over an UNKNOWN profile for a model that is also known in the catalog — the
     * very thing these tests need to control.
     */
    private fun registry(vararg profiles: ModelCapabilityProfile) =
        InMemoryModelCapabilityRegistry(initial = emptyList()).also { profiles.forEach(it::register) }

    private fun connection(id: String) = ModelConfig(
        providerId = provider,
        baseUrl = "http://localhost/$id/v1",
        model = shared,
        connectionId = id,
    )

    private fun option(
        connectionId: String,
        models: List<String> = listOf(shared),
        connected: Boolean = true,
    ) = ProviderModelOption(
        providerId = provider,
        providerLabel = "OpenAI-compatible",
        models = models,
        connected = connected,
        connectionId = connectionId,
        connectionLabel = connectionId,
        endpoint = "http://localhost/$connectionId/v1",
    )

    private fun selection(
        role: AgentRole = AgentRole.MAIN,
        model: String? = shared,
        connectionId: String? = null,
        providerId: String? = provider,
        explicit: Boolean = true,
    ) = RoleModelSelection(role, providerId, model, connectionId, explicit)

    private fun runtimeEligibility(
        registry: InMemoryModelCapabilityRegistry,
        connectionId: String,
    ) = runBlocking {
        ModelEligibilityChecker(registry).check(AgentRole.MAIN, connection(connectionId))
    }

    // --- the same model id on two connections ------------------------------

    @Test
    fun `an assignment to one connection is judged by that connection, not its sibling`() {
        val registry = registry(profile())
        val status = RoleModelEvaluation.evaluate(
            selection = selection(connectionId = "conn-a"),
            options = listOf(option("conn-a"), option("conn-b")),
            availableConnections = setOf("conn-a", "conn-b"),
            capabilities = registry,
        )

        assertEquals(RoleModelState.CONNECTED, status.state)
        assertEquals("conn-a", status.connectionId, "the exact connection is reported")
    }

    @Test
    fun `a sibling offering the same model does not make an assignment runnable`() {
        // conn-a is connected but now serves a different model; conn-b still serves the
        // assigned one. The role was assigned to conn-a, so the assignment is
        // unavailable — picking conn-b's copy would be silent substitution.
        val status = RoleModelEvaluation.evaluate(
            selection = selection(connectionId = "conn-a"),
            options = listOf(option("conn-a", models = listOf("some-other-model")), option("conn-b")),
            availableConnections = setOf("conn-a", "conn-b"),
            capabilities = registry(profile()),
        )

        assertNotEquals(RoleModelState.CONNECTED, status.state)
        assertEquals(RoleModelState.MODEL_UNAVAILABLE, status.state)
    }

    // --- a deleted connection is stale, not re-pointed ---------------------

    @Test
    fun `a deleted connection makes the assignment stale and never re-points it`() {
        val status = RoleModelEvaluation.evaluate(
            selection = selection(connectionId = "conn-a"),
            // Only the sibling survives.
            options = listOf(option("conn-b")),
            availableConnections = setOf("conn-b"),
            capabilities = registry(profile()),
        )

        assertEquals(
            RoleModelState.CONNECTION_MISSING,
            status.state,
            "a surviving sibling must not be reported as the assignment",
        )
        assertEquals("conn-a", status.connectionId, "the saved identity is preserved so it can be repaired")
        assertTrue(status.message.contains("no longer available"))
        assertTrue(!status.runtimeReady)
    }

    @Test
    fun `a connection set that was never reported does not invent a missing connection`() {
        // Null means "no runtime view", which is not the same as "this connection is
        // gone": a caller without runtime data must not produce a false stale alarm.
        val status = RoleModelEvaluation.evaluate(
            selection = selection(connectionId = "conn-a"),
            options = listOf(option("conn-a")),
            availableConnections = null,
            capabilities = registry(profile()),
        )

        assertEquals(RoleModelState.CONNECTED, status.state)
    }

    // --- capability verdicts, and agreement with the runtime ---------------

    @Test
    fun `an unconfirmed capability is visible but not reported ready`() {
        val registry = registry(profile(support = CapabilitySupport.UNKNOWN, known = false))
        val status = RoleModelEvaluation.evaluate(
            selection = selection(connectionId = "conn-a"),
            options = listOf(option("conn-a")),
            availableConnections = setOf("conn-a"),
            capabilities = registry,
        )

        assertEquals(RoleModelState.CAPABILITY_UNKNOWN, status.state)
        // Still shown, with a model and an explanation — unconfirmed is not invisible.
        assertEquals(shared, status.model)
        assertTrue(status.capabilityNote != null)
        assertTrue(!status.runtimeReady)

        val eligibility = runtimeEligibility(registry, "conn-a")
        assertEquals(ModelEligibilityState.UNKNOWN, eligibility.state)
        assertTrue(!eligibility.eligible, "the runtime must agree it cannot run yet")
    }

    @Test
    fun `a definite capability rejection is reported as unsupported in both places`() {
        val registry = registry(profile(support = CapabilitySupport.UNSUPPORTED))
        val status = RoleModelEvaluation.evaluate(
            selection = selection(connectionId = "conn-a"),
            options = listOf(option("conn-a")),
            availableConnections = setOf("conn-a"),
            capabilities = registry,
        )

        assertEquals(RoleModelState.CAPABILITY_UNSUPPORTED, status.state)
        val eligibility = runtimeEligibility(registry, "conn-a")
        assertEquals(ModelEligibilityState.CAPABILITY_UNSUPPORTED, eligibility.state)
        assertTrue(!eligibility.eligible)
    }

    @Test
    fun `a disabled model is disabled in both places`() {
        val registry = registry(profile(enabled = false))
        val status = RoleModelEvaluation.evaluate(
            selection = selection(connectionId = "conn-a"),
            options = listOf(option("conn-a")),
            availableConnections = setOf("conn-a"),
            capabilities = registry,
        )

        assertEquals(RoleModelState.DISABLED, status.state)
        assertEquals(ModelEligibilityState.DISABLED, runtimeEligibility(registry, "conn-a").state)
    }

    @Test
    fun `settings reports ready exactly when the runtime finds the model eligible`() {
        // The invariant, stated once for every capability verdict: Settings must never
        // claim an assignment is ready while the runtime refuses it.
        val cases = listOf(
            "supported" to profile(support = CapabilitySupport.SUPPORTED),
            "unsupported" to profile(support = CapabilitySupport.UNSUPPORTED),
            "unknown" to profile(support = CapabilitySupport.UNKNOWN, known = false),
            "disabled" to profile(enabled = false),
        )

        assertTrue(
            AgentRoleRequirements.required(AgentRole.MAIN).isNotEmpty(),
            "MAIN must require at least one capability for this invariant to mean anything",
        )

        cases.forEach { (label, definition) ->
            val registry = registry(definition)
            val status = RoleModelEvaluation.evaluate(
                selection = selection(connectionId = "conn-a"),
                options = listOf(option("conn-a")),
                availableConnections = setOf("conn-a"),
                capabilities = registry,
            )
            val eligibility = runtimeEligibility(registry, "conn-a")

            assertEquals(
                eligibility.eligible,
                status.runtimeReady,
                "$label: Settings (${status.state}) and the runtime (${eligibility.state}) must agree",
            )
        }
    }

    @Test
    fun `without a capability registry the evaluation makes no capability claim`() {
        // A caller with no registry must not be told a model is unsupported; the
        // capability verdict simply is not available to it.
        val status = RoleModelEvaluation.evaluate(
            selection = selection(connectionId = "conn-a"),
            options = listOf(option("conn-a")),
            availableConnections = setOf("conn-a"),
            capabilities = null,
        )

        assertEquals(RoleModelState.CONNECTED, status.state)
        assertEquals(null, status.capabilityNote)
    }

    // --- the persisted assignment reaches the same conclusion --------------

    @Test
    fun `a saved assignment resolves to its exact connection and refuses a substitute`() {
        val saved = RoleModelConfig(
            role = AgentRole.MAIN,
            providerId = provider,
            model = shared,
            connectionId = "conn-a",
        )
        // What persistence restores: the role mapping built from the saved record.
        val preferences = AgentModelPreferences(mapOf(AgentRole.MAIN to saved.toPreference()))

        val connected = AgentModelResolver(
            preferences = preferences,
            connections = { mapOf("conn-a" to connection("conn-a"), "conn-b" to connection("conn-b")) },
        )
        val resolved = runBlocking { connected.resolveForRole(AgentRole.MAIN, default = connection("conn-b")) }
        assertEquals("conn-a", resolved.config.connectionId, "the saved connection is honored")
        assertEquals(shared, resolved.config.model)
        assertTrue(resolved.explicit, "a saved assignment is authoritative")

        // Reload with conn-a gone: the assignment fails explicitly rather than being
        // answered by conn-b, which exposes the same model id.
        val goneResolver = AgentModelResolver(
            preferences = preferences,
            connections = { mapOf("conn-b" to connection("conn-b")) },
        )
        val failure = assertFailsWith<AgentModelResolutionException> {
            runBlocking { goneResolver.resolveForRole(AgentRole.MAIN, default = connection("conn-b")) }
        }
        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals("conn-a", failure.error.details["connection"])

        // And Settings says the same thing about the same state.
        val status = RoleModelEvaluation.evaluate(
            selection = RoleModelSelection(
                role = AgentRole.MAIN,
                providerId = provider,
                model = shared,
                connectionId = "conn-a",
                explicit = true,
            ),
            options = listOf(option("conn-b")),
            availableConnections = setOf("conn-b"),
            capabilities = registry(profile()),
        )
        assertEquals(RoleModelState.CONNECTION_MISSING, status.state)
        assertTrue(!status.runtimeReady)
    }

    @Test
    fun `an assignment with no connection identity keeps the provider family behaviour`() {
        // The built-in defaults name a provider, not a connection, and must keep working.
        val status = RoleModelEvaluation.evaluate(
            selection = selection(connectionId = null),
            options = listOf(option("conn-a")),
            availableConnections = setOf("conn-a"),
            capabilities = registry(profile()),
        )

        assertEquals(RoleModelState.CONNECTED, status.state)
        assertEquals(null, status.connectionId)
    }
}
