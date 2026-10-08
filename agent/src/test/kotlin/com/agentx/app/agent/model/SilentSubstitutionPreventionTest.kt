package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.domain.ModelFallbackReason
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelProviderError
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.health.CandidateFailure
import com.agentx.app.model.health.CandidateHealthTracker
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An explicit role → model assignment is authoritative: when the assigned
 * connection/model cannot be used, resolution fails with a structured error and
 * the role is never silently executed on another model.
 *
 * These tests drive the real [AgentModelResolver] and its shared eligibility
 * checker. No provider is contacted; the only substitutes are the caller's own
 * eligible candidates, which must stay unused.
 */
class SilentSubstitutionPreventionTest {

    private fun capable(connectionId: String, providerId: String, model: String) = ModelConfig(
        providerId = providerId,
        baseUrl = "https://$connectionId.example.dev/v1",
        model = model,
        capabilities = ModelCapabilities(toolCalling = true, streaming = true),
        connectionId = connectionId,
    )

    /** A connected connection whose capabilities are deliberately unknown. */
    private fun unknownCapability(connectionId: String, providerId: String, model: String) = ModelConfig(
        providerId = providerId,
        baseUrl = "https://$connectionId.example.dev/v1",
        model = model,
        connectionId = connectionId,
    )

    private fun explicit(role: AgentRole, providerId: String, connectionId: String, model: String) =
        AgentModelPreferences().with(
            role,
            RoleModelPreference(providerId, model, connectionId = connectionId, explicit = true),
        )

    private fun resolver(
        preferences: AgentModelPreferences,
        connections: Map<String, ModelConfig>,
        health: CandidateHealthTracker? = null,
        fallbackConfigured: Boolean = false,
    ) = AgentModelResolver(
        preferences = preferences,
        connections = { connections },
        capabilityRegistry = InMemoryModelCapabilityRegistry(initial = emptyList()),
        healthTracker = health,
        intentionalFallback = { fallbackConfigured },
    )

    private val modelA = capable("conn-a", "provider-a", "model-a")
    private val modelB = capable("conn-b", "provider-b", "model-b")

    // --- A. explicit model available ---------------------------------------

    @Test
    fun `A an available explicit assignment is used and reported explicit`() = runBlocking {
        val resolver = resolver(explicit(AgentRole.MAIN, "provider-a", "conn-a", "model-a"), mapOf("conn-a" to modelA))

        val result = resolver.resolveForRole(AgentRole.MAIN, default = modelB)

        assertTrue(result.eligible)
        assertEquals("model-a", result.config.model)
        assertEquals("conn-a", result.config.connectionId)
        assertEquals("provider-a", result.config.providerId)
        assertTrue(result.explicit)
    }

    // --- B. explicit model unavailable -------------------------------------

    @Test
    fun `B an unavailable explicit connection fails and never uses another model`() = runBlocking {
        // Only model B's connection is connected; the role explicitly names conn-a.
        val resolver = resolver(explicit(AgentRole.MAIN, "provider-a", "conn-a", "model-a"), mapOf("conn-b" to modelB))

        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver.resolveForRole(AgentRole.MAIN, default = modelB)
        }
        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals(AgentRole.MAIN, failure.error.role)
        assertEquals("conn-a", failure.error.details["connection"])
        assertEquals("provider-a", failure.error.details["provider"])
        assertEquals("model-a", failure.error.details["model"])
        assertEquals("false", failure.error.details["fallbackAvailable"])
        // The pure selection path fails the same way instead of substituting.
        assertFailsWith<AgentModelResolutionException> {
            resolver.resolve(AgentRole.MAIN, default = modelB)
        }
    }

    // --- C. explicit model capability-ineligible ---------------------------

    @Test
    fun `C an explicit capability-ineligible model fails and never uses an eligible one`() = runBlocking {
        val noTools = ModelConfig(
            providerId = "provider-a",
            baseUrl = "https://conn-a.example.dev/v1",
            model = "model-a",
            capabilities = ModelCapabilities(toolCalling = false, streaming = true),
            connectionId = "conn-a",
        )
        val resolver = resolver(
            explicit(AgentRole.CODER, "provider-a", "conn-a", "model-a"),
            mapOf("conn-a" to noTools, "conn-b" to modelB),
        )

        val result = resolver.resolveForRole(AgentRole.CODER, default = modelB)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.CAPABILITY_UNSUPPORTED, result.eligibility.state)
        // The configured identity is preserved, not swapped for model B.
        assertEquals("conn-a", result.config.connectionId)
        assertEquals("model-a", result.config.model)
        val error = assertFailsWith<AgentModelResolutionException> { result.eligibleConfigOrThrow() }.error
        assertEquals(AgentErrorCode.MODEL_NOT_ELIGIBLE, error.code)
        assertEquals("MODEL_NOT_ELIGIBLE", error.details["cause"])
    }

    // --- D. explicit model UNKNOWN capability ------------------------------

    @Test
    fun `D an explicit model with unknown capability fails and never uses another model`() = runBlocking {
        val resolver = resolver(
            explicit(AgentRole.CODER, "provider-a", "conn-a", "model-a"),
            mapOf("conn-a" to unknownCapability("conn-a", "provider-a", "model-a"), "conn-b" to modelB),
        )

        val result = resolver.resolveForRole(AgentRole.CODER, default = modelB)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
        assertEquals("conn-a", result.config.connectionId)
        assertEquals("model-a", result.config.model)
        val error = assertFailsWith<AgentModelResolutionException> { result.eligibleConfigOrThrow() }.error
        assertTrue(error.message.contains("reason=UNKNOWN"))
        assertEquals("MODEL_CAPABILITY_UNKNOWN", error.details["cause"])
    }

    // --- E. explicit model connection degraded -----------------------------

    @Test
    fun `E a degraded explicit connection fails and never uses a healthy one`() = runBlocking {
        val health = CandidateHealthTracker()
        // Rejected credentials are a provider-wide, non-cooldown problem: the
        // candidate must not be asked, however healthy another connection looks.
        health.recordFailure(providerId = "provider-a", modelId = "model-a", failure = CandidateFailure.AUTHENTICATION)
        val resolver = resolver(
            explicit(AgentRole.REVIEWER, "provider-a", "conn-a", "model-a"),
            mapOf("conn-a" to modelA, "conn-b" to modelB),
            health = health,
        )

        val result = resolver.resolveForRole(AgentRole.REVIEWER, default = modelB)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.PROVIDER_UNHEALTHY, result.eligibility.state)
        assertEquals("conn-a", result.config.connectionId)
        assertEquals("model-a", result.config.model)
        val error = assertFailsWith<AgentModelResolutionException> { result.eligibleConfigOrThrow() }.error
        assertEquals("CONNECTION_DEGRADED", error.details["cause"])
    }

    // --- F. intentional fallback stays explicit and opt-in -----------------

    @Test
    fun `F an intentional fallback is reported available but is never triggered by resolution`() = runBlocking {
        val prefs = explicit(AgentRole.MAIN, "provider-a", "conn-a", "model-a")

        val without = resolver(prefs, mapOf("conn-b" to modelB))
        val withoutError = assertFailsWith<AgentModelResolutionException> {
            without.resolveForRole(AgentRole.MAIN, default = modelB)
        }.error
        assertEquals("false", withoutError.details["fallbackAvailable"])

        val with = resolver(prefs, mapOf("conn-b" to modelB), fallbackConfigured = true)
        val withError = assertFailsWith<AgentModelResolutionException> {
            with.resolveForRole(AgentRole.MAIN, default = modelB)
        }.error
        // The configured fallback is reported, but resolution itself selects nothing.
        assertEquals("true", withError.details["fallbackAvailable"])
        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, withError.code)
    }

    @Test
    fun `F an enabled fallback policy substitutes explicitly and carries connection identity`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = mutableListOf<ModelConfig>()
        val policy = ModelFallbackPolicy(
            automaticFallback = true,
            fallbacksByRole = mapOf(
                AgentRole.MAIN to listOf(
                    RoleModelPreference("provider-b", "model-b", connectionId = "conn-b", explicit = true),
                ),
            ),
        )
        val fallback = ModelFallback(
            policy = { policy },
            resolver = resolver(AgentModelPreferences.EMPTY, mapOf("conn-b" to modelB)),
        )

        val result = fallback.execute(
            role = AgentRole.MAIN,
            sessionId = "s",
            primary = modelA,
            sink = sink,
            call = { config ->
                calls += config
                if (config.connectionId == "conn-a") {
                    throw ModelProviderError(ModelProviderErrorCode.NETWORK_ERROR, "net", "provider-a")
                }
                "ok"
            },
        )

        assertEquals("ok", result)
        assertEquals(listOf("conn-a", "conn-b"), calls.map { it.connectionId })
        val started = sink.events.filterIsInstance<AgentEvent.ModelFallbackStarted>().single()
        assertEquals("conn-a", started.fromConnectionId)
        assertEquals("conn-b", started.toConnectionId)
        assertEquals(ModelFallbackReason.NETWORK_FAILURE, started.reason)
    }

    @Test
    fun `F a disabled fallback policy never substitutes the explicit model`() = runBlocking {
        val sink = CollectingEventSink()
        val calls = mutableListOf<ModelConfig>()
        val fallback = ModelFallback(
            policy = { ModelFallbackPolicy.DISABLED },
            resolver = resolver(AgentModelPreferences.EMPTY, mapOf("conn-b" to modelB)),
        )

        assertFailsWith<ModelProviderError> {
            fallback.execute(
                role = AgentRole.MAIN,
                sessionId = "s",
                primary = modelA,
                sink = sink,
                call = { config ->
                    calls += config
                    throw ModelProviderError(ModelProviderErrorCode.NETWORK_ERROR, "net", "provider-a")
                },
            )
        }
        assertEquals(listOf("conn-a"), calls.map { it.connectionId })
        assertTrue(sink.events.none { it is AgentEvent.ModelFallbackStarted })
    }

    // --- G. no explicit assignment -----------------------------------------

    @Test
    fun `G a role without an explicit assignment keeps normal policy selection`() = runBlocking {
        val active = capable("active", "provider-active", "active-model")
        val result = resolver(AgentModelPreferences.EMPTY, emptyMap())
            .resolveForRole(AgentRole.MAIN, default = active)

        assertTrue(result.eligible)
        assertEquals(active, result.config)
        assertFalse(result.explicit)
        assertEquals("active-model", result.config.model)
    }

    // --- H. multi-model isolation ------------------------------------------

    @Test
    fun `H one unavailable role model does not replace another role's model`() = runBlocking {
        val a = capable("conn-a", "provider-a", "model-a")
        val c = capable("conn-c", "provider-c", "model-c")
        val d = capable("conn-d", "provider-d", "model-d")
        val prefs = AgentModelPreferences.EMPTY
            .with(AgentRole.MAIN, RoleModelPreference("provider-a", "model-a", connectionId = "conn-a", explicit = true))
            .with(AgentRole.CODER, RoleModelPreference("provider-b", "model-b", connectionId = "conn-b", explicit = true))
            .with(AgentRole.REVIEWER, RoleModelPreference("provider-c", "model-c", connectionId = "conn-c", explicit = true))
            .with(AgentRole.EXPLORER, RoleModelPreference("provider-d", "model-d", connectionId = "conn-d", explicit = true))
        // CODER's connection is gone; the other three remain connected.
        val resolver = resolver(prefs, mapOf("conn-a" to a, "conn-c" to c, "conn-d" to d))

        assertEquals("model-a", resolver.resolveForRole(AgentRole.MAIN, default = a).config.model)
        assertEquals("model-c", resolver.resolveForRole(AgentRole.REVIEWER, default = a).config.model)
        assertEquals("model-d", resolver.resolveForRole(AgentRole.EXPLORER, default = a).config.model)
        assertEquals("conn-c", resolver.resolveForRole(AgentRole.REVIEWER, default = a).config.connectionId)
        // The one unavailable role fails; it does not borrow another role's model.
        val failure = assertFailsWith<AgentModelResolutionException> {
            resolver.resolveForRole(AgentRole.CODER, default = a)
        }
        assertEquals(AgentErrorCode.MODEL_NOT_CONNECTED, failure.error.code)
        assertEquals("conn-b", failure.error.details["connection"])
    }
}
