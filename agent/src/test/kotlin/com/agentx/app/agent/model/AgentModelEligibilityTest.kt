package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.testDomain
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityProfile
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.ratelimit.DefaultRateLimitManager
import com.agentx.app.model.ratelimit.RateLimitProfile
import com.agentx.app.model.ratelimit.RateLimitSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capability- and quota-aware selection. The rate-limit manager is the real
 * [DefaultRateLimitManager] driven by deterministic profiles (a zero request
 * limit blocks immediately); no provider API is ever contacted. The resolver
 * only *evaluates* admission here and never reserves quota.
 */
class AgentModelEligibilityTest {

    private fun config(
        providerId: String,
        model: String,
        baseUrl: String = "https://$providerId.example/v1",
        capabilities: ModelCapabilities? = null,
    ) = ModelConfig(
        providerId = providerId,
        baseUrl = baseUrl,
        model = model,
        capabilities = capabilities,
        connectionKind = testDomain(providerId),
    )

    private fun localConfig() = config(
        providerId = AgentModelProviders.OPENAI_COMPATIBLE_LOCAL,
        model = AgentModelIds.DEVSTRAL_24B,
        baseUrl = "http://localhost:11434/v1",
    )

    private val active = config("active", "active-model")

    private fun manager(vararg profiles: RateLimitProfile): DefaultRateLimitManager {
        val manager = DefaultRateLimitManager()
        runBlocking { profiles.forEach { manager.updateProfile(it) } }
        return manager
    }

    private fun blocked(providerId: String, modelId: String? = null) = RateLimitProfile(
        providerId = providerId,
        modelId = modelId,
        requestsPerMinute = 0,
        source = RateLimitSource.APP_CONFIGURED,
    )

    private fun resolver(
        connections: Map<String, ModelConfig>,
        rateLimitManager: DefaultRateLimitManager? = null,
        capabilityRegistry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry.DEFAULT,
        preferences: AgentModelPreferences = AgentModelPreferences.DEFAULT,
    ) = AgentModelResolver(
        preferences = preferences,
        connections = { connections },
        capabilityRegistry = capabilityRegistry,
        rateLimitManager = rateLimitManager,
    )

    @Test
    fun `an eligible main model resolves with capability and quota information`() = runBlocking {
        val connections = mapOf(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL to localConfig())
        val result = resolver(connections, manager()).resolveForRole(AgentRole.MAIN, active)

        assertTrue(result.eligible)
        assertEquals(ModelEligibilityState.AVAILABLE, result.eligibility.state)
        assertEquals(AgentModelIds.DEVSTRAL_24B, result.config.model)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, result.config.providerId)
        assertTrue(result.eligibility.profile.supports(ModelCapability.TOOL_CALLING))
        assertTrue(result.eligibility.profile.supports(ModelCapability.STREAMING))
        assertTrue(result.explicit)
        assertNull(result.errorOrNull())
        assertEquals(result.config, result.eligibleConfigOrThrow())
    }

    @Test
    fun `an eligible local coder model is not blocked by a remote quota`() = runBlocking {
        // A zero request-per-minute limit would block a remote request outright.
        val limits = manager(blocked(AgentModelProviders.OPENAI_COMPATIBLE))
        val connections = mapOf(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL to localConfig())

        val result = resolver(connections, limits).resolveForRole(AgentRole.CODER, active)

        assertTrue(result.eligible)
        assertEquals(ModelEligibilityState.AVAILABLE, result.eligibility.state)
        assertEquals(AgentModelIds.DEVSTRAL_24B, result.config.model)
        assertTrue(result.eligibility.local)
    }

    @Test
    fun `an unknown model for a tool enabled role is not eligible`() = runBlocking {
        // The role is explicitly assigned a model with no authoritative definition.
        val preferences = AgentModelPreferences()
            .with(AgentRole.CODER, RoleModelPreference(AgentModelProviders.GROQ, "allam-2-7b"))
        val connections = mapOf(
            AgentModelProviders.GROQ to config(AgentModelProviders.GROQ, "allam-2-7b"),
        )
        val result = resolver(connections, preferences = preferences).resolveForRole(AgentRole.CODER, active)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
        // An unknown model confirms neither required capability.
        assertEquals(
            setOf(ModelCapability.TOOL_CALLING, ModelCapability.STREAMING),
            result.eligibility.missingCapabilities.toSet(),
        )
        val error = assertNotNull(result.errorOrNull())
        assertEquals(AgentErrorCode.MODEL_NOT_ELIGIBLE, error.code)
        assertTrue(error.message.contains("reason=UNKNOWN"))
        assertNull(result.configOrNull())
    }

    @Test
    fun `a registered discovered model is eligible for lookup without becoming tool capable`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry()
        registry.register(
            ModelCapabilityProfile.discovered(
                providerId = AgentModelProviders.GROQ,
                modelId = "allam-2-7b",
            ),
        )
        val preferences = AgentModelPreferences()
            .with(AgentRole.CODER, RoleModelPreference(AgentModelProviders.GROQ, "allam-2-7b"))
        val connections = mapOf(
            AgentModelProviders.GROQ to config(AgentModelProviders.GROQ, "allam-2-7b"),
        )
        val result = resolver(connections, capabilityRegistry = registry, preferences = preferences)
            .resolveForRole(AgentRole.CODER, active)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
        assertEquals(AgentModelProviders.GROQ, result.config.providerId)
        assertEquals("allam-2-7b", result.config.model)
        assertTrue(result.explicit)
        assertFalse(result.eligibility.profile.known)
        assertEquals(CapabilitySupport.UNKNOWN, result.eligibility.profile.toolCalling)
        assertNull(result.configOrNull())
    }

    @Test
    fun `a model without tool calling is rejected`() = runBlocking {
        val connections = mapOf(
            AgentModelProviders.FREELMAPI to config(
                AgentModelProviders.FREELMAPI,
                AgentModelIds.FREELLMAPI_GEMINI,
                capabilities = ModelCapabilities(toolCalling = false, streaming = true),
            ),
        )
        val preferences = AgentModelPreferences().with(
            AgentRole.CODER,
            RoleModelPreference(AgentModelProviders.FREELMAPI, AgentModelIds.FREELLMAPI_GEMINI),
        )
        val result = resolver(connections, preferences = preferences).resolveForRole(AgentRole.CODER, active)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.CAPABILITY_UNSUPPORTED, result.eligibility.state)
        assertEquals(listOf(ModelCapability.TOOL_CALLING), result.eligibility.missingCapabilities)
        val error = assertNotNull(result.errorOrNull())
        assertTrue(error.message.contains("reason=CAPABILITY_UNSUPPORTED"))
        assertTrue(error.message.contains("capability=toolCalling"))
        assertEquals("toolCalling", error.details["capability"])
    }

    @Test
    fun `a model without streaming is rejected`() = runBlocking {
        val connections = mapOf(
            AgentModelProviders.GROQ to config(
                AgentModelProviders.GROQ,
                AgentModelIds.GROQ,
                capabilities = ModelCapabilities(toolCalling = true, streaming = false),
            ),
        )
        val result = resolver(connections).resolveForRole(AgentRole.TESTER, active)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.CAPABILITY_UNSUPPORTED, result.eligibility.state)
        assertEquals(listOf(ModelCapability.STREAMING), result.eligibility.missingCapabilities)
        assertEquals("streaming", result.eligibility.rejectionError().details["capability"])
    }

    @Test
    fun `a rate-limited model is reported as unavailable`() = runBlocking {
        val limits = manager(blocked(AgentModelProviders.FREELMAPI))
        val connections = mapOf(
            AgentModelProviders.FREELMAPI to config(AgentModelProviders.FREELMAPI, AgentModelIds.FREELLMAPI_GROQ),
        )
        val result = resolver(connections, limits).resolveForRole(AgentRole.EXPLORER, active)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.RATE_LIMITED, result.eligibility.state)
        assertNotNull(result.eligibility.retryAfterMs)
        val error = assertNotNull(result.errorOrNull())
        assertEquals(AgentErrorCode.MODEL_NOT_ELIGIBLE, error.code)
        assertTrue(error.message.contains("reason=RATE_LIMITED"))
        assertTrue(error.message.contains("retryAfterMs="))
        assertNotNull(error.details["retryAfterMs"])
        assertNull(result.configOrNull())
    }

    @Test
    fun `unknown rate-limit metadata does not block a capable model`() = runBlocking {
        val connections = mapOf(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL to localConfig())
        // No profiles configured: the manager knows no limits, so it never guesses a block.
        val result = resolver(connections, manager()).resolveForRole(AgentRole.MAIN, active)
        assertTrue(result.eligible)
        assertEquals(ModelEligibilityState.AVAILABLE, result.eligibility.state)

        // The same holds when no manager is wired at all (tests, headless hosts).
        val unmanaged = resolver(connections).resolveForRole(AgentRole.MAIN, active)
        assertTrue(unmanaged.eligible)
    }

    @Test
    fun `a disabled model is not eligible`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry()
        registry.register(
            ModelCapabilityProfile(
                providerId = AgentModelProviders.GEMINI,
                modelId = "gemini-3.5-flash-disabled",
                displayName = "Disabled Flash",
                toolCalling = CapabilitySupport.SUPPORTED,
                streaming = CapabilitySupport.SUPPORTED,
                enabled = false,
            ),
        )
        val preferences = AgentModelPreferences()
            .with(
                AgentRole.MAIN,
                RoleModelPreference(AgentModelProviders.GEMINI, "gemini-3.5-flash-disabled"),
            )
        val connections = mapOf(
            AgentModelProviders.GEMINI to config(AgentModelProviders.GEMINI, "gemini-3.5-flash-disabled"),
        )
        val result = resolver(connections, capabilityRegistry = registry, preferences = preferences)
            .resolveForRole(AgentRole.MAIN, active)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.DISABLED, result.eligibility.state)
        assertTrue(result.eligibility.rejectionError().message.contains("reason=DISABLED"))
    }

    @Test
    fun `an explicit role assignment stays explicit and is never silently replaced`() = runBlocking {
        val limits = manager(blocked(AgentModelProviders.FREELMAPI))
        val connections = mapOf(
            AgentModelProviders.FREELMAPI to config(AgentModelProviders.FREELMAPI, AgentModelIds.FREELLMAPI_GROQ),
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL to localConfig(),
        )
        val result = resolver(connections, limits).resolveForRole(AgentRole.EXPLORER, active)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.RATE_LIMITED, result.eligibility.state)
        // The API assignment is retained (and explained), never swapped for the
        // eligible local connection.
        assertEquals(AgentModelProviders.FREELMAPI, result.config.providerId)
        assertEquals(AgentModelIds.FREELLMAPI_GROQ, result.config.model)
        assertTrue(result.explicit)
    }

    @Test
    fun `multiple providers keep isolated eligibility`() = runBlocking {
        val limits = manager(blocked(AgentModelProviders.FREELMAPI))
        val connections = mapOf(
            AgentModelProviders.FREELMAPI to config(AgentModelProviders.FREELMAPI, AgentModelIds.FREELLMAPI_GROQ),
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL to localConfig(),
        )
        val live = resolver(connections, limits)

        val main = live.resolveForRole(AgentRole.MAIN, active)
        val explorer = live.resolveForRole(AgentRole.EXPLORER, active)

        assertTrue(main.eligible)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE_LOCAL, main.config.providerId)
        assertFalse(explorer.eligible)
        assertEquals(ModelEligibilityState.RATE_LIMITED, explorer.eligibility.state)
        assertEquals(AgentModelProviders.FREELMAPI, explorer.config.providerId)
    }

    @Test
    fun `existing resolver selection remains compatible`() = runBlocking {
        val resolver = AgentModelResolver()
        assertEquals(active, resolver.resolve(AgentRole.CODER, active))

        val capable = config(
            "active",
            "active-model",
            capabilities = ModelCapabilities(toolCalling = true, streaming = true),
        )
        assertTrue(resolver.canSatisfy(AgentRole.CODER, capable))
        assertEquals(capable, resolver.resolveChecked(AgentRole.CODER, capable))

        val result = resolver.resolveForRole(AgentRole.CODER, capable)
        assertTrue(result.eligible)
        assertEquals(capable, result.config)
        assertFalse(result.explicit)
    }

    @Test
    fun `every role resolves through the shared eligibility check`() = runBlocking {
        val connections = mapOf(
            AgentModelProviders.OPENAI_COMPATIBLE_LOCAL to localConfig(),
            AgentModelProviders.FREELMAPI to config(AgentModelProviders.FREELMAPI, AgentModelIds.FREELLMAPI_GEMINI),
            AgentModelProviders.GROQ to config(AgentModelProviders.GROQ, AgentModelIds.GROQ),
        )
        val live = resolver(connections, manager())
        val capableDefault = config(
            "active",
            "active-model",
            capabilities = ModelCapabilities(toolCalling = true, streaming = true),
        )

        AgentRole.entries.forEach { role ->
            val fallback = if (AgentModelPreferences.DEFAULT[role] == null) capableDefault else active
            val result = live.resolveForRole(role, fallback)
            assertTrue(result.eligible, "role ${role.name} should resolve an eligible model")
            // Only the roles that genuinely act on the workspace require the tool
            // contract; analysis roles resolve on text generation alone.
            if (AgentRoleRequirements.requiresToolCalling(role)) {
                assertTrue(result.eligibility.profile.supports(ModelCapability.TOOL_CALLING), role.name)
                assertTrue(result.eligibility.profile.supports(ModelCapability.STREAMING), role.name)
            }
        }
    }
}
