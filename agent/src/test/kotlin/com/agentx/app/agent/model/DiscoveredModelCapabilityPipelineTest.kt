package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.ModelCapabilities
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilityProvenance
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.capability.ModelCapabilityProfile
import com.agentx.app.model.catalog.InMemoryModelCatalogStore
import com.agentx.app.model.catalog.RemoteModelCatalog
import com.agentx.app.model.discovery.DiscoveredModel
import com.agentx.app.model.discovery.ModelDiscovery
import com.agentx.app.model.discovery.ModelDiscoveryOutcome
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The discovery → capability descriptor → capability registry → role eligibility
 * pipeline, end to end.
 *
 * The one rule every case here enforces: a discovered model is identified by its
 * provider + model id, its *capability* is only what an authoritative definition,
 * the provider's own metadata, or an explicit declaration says, and its *role
 * eligibility* is decided from that capability alone. A discovered model is never
 * silently treated as tool-capable, and never silently replaced.
 */
class DiscoveredModelCapabilityPipelineTest {

    private fun config(
        providerId: String,
        model: String,
        baseUrl: String = "https://$providerId.example.dev/v1",
        capabilities: ModelCapabilities? = null,
        declaredCapabilities: ModelCapabilityDeclaration? = null,
        connectionId: String = providerId,
    ) = ModelConfig(
        providerId = providerId,
        baseUrl = baseUrl,
        model = model,
        capabilities = capabilities,
        declaredCapabilities = declaredCapabilities,
        connectionId = connectionId,
    )

    // --- A. static known model ---------------------------------------------

    @Test
    fun `a known tool capable model is eligible for a compatible role`() = runBlocking {
        val resolver = AgentModelResolver(capabilityRegistry = InMemoryModelCapabilityRegistry())
        val selected = config("gemini", "gemini-3.5-flash", baseUrl = "https://generativelanguage.googleapis.com/v1beta")

        val result = resolver.resolveForRole(AgentRole.MAIN, selected)

        assertTrue(result.eligible)
        assertEquals(ModelEligibilityState.AVAILABLE, result.eligibility.state)
        assertTrue(result.eligibility.profile.supports(ModelCapability.TOOL_CALLING))
        assertTrue(result.eligibility.profile.supports(ModelCapability.STREAMING))
    }

    // --- B. discovered model whose capabilities are established -------------

    @Test
    fun `a discovered model that matches a known definition keeps its capabilities`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry()
        // Discovery registers the identity; the authoritative definition it matches
        // must survive, so the discovered model stays usable for its role.
        registry.register(
            ModelCapabilityProfile.discovered("openai-compatible", "qwen2.5-coder-14b"),
        )

        val profile = assertNotNull(registry.get("openai-compatible", "qwen2.5-coder-14b"))
        assertEquals(CapabilitySupport.SUPPORTED, profile.toolCalling)
        assertEquals(CapabilitySupport.SUPPORTED, profile.streaming)

        val resolver = AgentModelResolver(capabilityRegistry = registry)
        val selected = config(
            providerId = "openai-compatible",
            model = "qwen2.5-coder-14b",
            baseUrl = "http://localhost:11434/v1",
        )
        val result = resolver.resolveForRole(AgentRole.CODER, selected)

        assertTrue(result.eligible)
        assertEquals("qwen2.5-coder-14b", result.config.model)
    }

    @Test
    fun `a discovered model with an explicit declaration is eligible for its role`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry(initial = emptyList())
        registry.register(ModelCapabilityProfile.discovered("openai-compatible", "brand-new-tool-model"))

        // The declaration travels on the configuration (per model, per connection),
        // so the discovered model becomes usable without marking the whole provider
        // family tool-capable.
        val selected = config(
            providerId = "openai-compatible",
            model = "brand-new-tool-model",
            declaredCapabilities = ModelCapabilityDeclaration.toolEnabledEndpoint(),
        )

        val resolver = AgentModelResolver(capabilityRegistry = registry)
        val result = resolver.resolveForRole(AgentRole.CODER, selected)

        assertTrue(result.eligible)
        assertTrue(result.eligibility.profile.toolCalling.isSupported)
        // The registry entry itself was never upgraded by the declaration.
        assertEquals(
            CapabilitySupport.UNKNOWN,
            assertNotNull(registry.get("openai-compatible", "brand-new-tool-model")).toolCalling,
        )
    }

    // --- C. discovered model with UNKNOWN tool calling ----------------------

    @Test
    fun `a discovered model with unknown tool calling is rejected with a clear reason`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry(initial = emptyList())
        registry.register(ModelCapabilityProfile.discovered("openai-compatible", "mystery-model"))

        val resolver = AgentModelResolver(capabilityRegistry = registry)
        val result = resolver.resolveForRole(
            AgentRole.CODER,
            config("openai-compatible", "mystery-model", baseUrl = "https://mystery.example.dev/v1"),
        )

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
        assertEquals(
            setOf(ModelCapability.TOOL_CALLING, ModelCapability.STREAMING),
            result.eligibility.missingCapabilities.toSet(),
        )
        val error = assertNotNull(result.errorOrNull())
        assertTrue(error.message.contains("reason=UNKNOWN"))
        assertTrue(error.message.contains("toolCalling"))
        // Nothing was substituted.
        assertEquals("mystery-model", result.config.model)
    }

    // --- D. explicitly unsupported capability -------------------------------

    @Test
    fun `an explicitly unsupported capability rejects the model`() = runBlocking {
        val resolver = AgentModelResolver(capabilityRegistry = InMemoryModelCapabilityRegistry(initial = emptyList()))
        val selected = config(
            providerId = "openai-compatible",
            model = "no-tools-model",
            capabilities = ModelCapabilities(toolCalling = false, streaming = true),
        )

        val result = resolver.resolveForRole(AgentRole.CODER, selected)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.CAPABILITY_UNSUPPORTED, result.eligibility.state)
        assertEquals(listOf(ModelCapability.TOOL_CALLING), result.eligibility.missingCapabilities)
        assertTrue(assertNotNull(result.errorOrNull()).message.contains("reason=CAPABILITY_UNSUPPORTED"))
    }

    // --- E. multiple connections, same provider family ----------------------

    @Test
    fun `two connections of one family keep separate discovered identities`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry(initial = emptyList())
        // Only connection A's model is tool capable.
        registry.register(
            ModelCapabilityProfile(
                providerId = "openai-compatible",
                modelId = "model-a",
                displayName = "Model A",
                toolCalling = CapabilitySupport.SUPPORTED,
                streaming = CapabilitySupport.SUPPORTED,
            ),
        )
        val connections = mapOf(
            "custom-a" to config("openai-compatible", "model-a", baseUrl = "https://a.example.dev/v1", connectionId = "custom-a"),
            "custom-b" to config("openai-compatible", "model-b", baseUrl = "https://b.example.dev/v1", connectionId = "custom-b"),
        )
        val preferencesA = AgentModelPreferences().with(
            AgentRole.CODER,
            RoleModelPreference("openai-compatible", "model-a", connectionId = "custom-a"),
        )
        val resolverA = AgentModelResolver(
            preferences = preferencesA,
            connections = { connections },
            capabilityRegistry = registry,
        )

        val resultA = resolverA.resolveForRole(AgentRole.CODER, connections.getValue("custom-b"))

        assertTrue(resultA.eligible)
        assertEquals("custom-a", resultA.config.connectionId)
        assertEquals("model-a", resultA.config.model)
        // The other connection's model is a distinct identity with no capability.
        assertEquals(CapabilitySupport.UNKNOWN, registry.profile("openai-compatible", "model-b").toolCalling)

        val preferencesB = AgentModelPreferences().with(
            AgentRole.CODER,
            RoleModelPreference("openai-compatible", "model-b", connectionId = "custom-b"),
        )
        val resolverB = AgentModelResolver(
            preferences = preferencesB,
            connections = { connections },
            capabilityRegistry = registry,
        )
        val resultB = resolverB.resolveForRole(AgentRole.CODER, connections.getValue("custom-a"))

        // Connection A's capability did not leak to connection B, and B's own model
        // was never replaced by A's.
        assertFalse(resultB.eligible)
        assertEquals("custom-b", resultB.config.connectionId)
        assertEquals("model-b", resultB.config.model)
    }

    // --- F. role-specific eligibility ---------------------------------------

    @Test
    fun `eligibility follows the role and request requirements, not a shortcut`() = runBlocking {
        // The role contract is the source of truth.
        assertEquals(AgentRoleRequirements.TOOL_ENABLED, AgentRoleRequirements.required(AgentRole.MAIN))
        assertEquals(
            AgentRoleRequirements.TOOL_ENABLED + ModelCapability.REASONING,
            ModelRequestRequirements(additionalCapabilities = setOf(ModelCapability.REASONING))
                .requiredFor(AgentRole.MAIN),
        )

        val resolver = AgentModelResolver(capabilityRegistry = InMemoryModelCapabilityRegistry())
        // Gemini is tool-capable and streaming, but the registry makes no reasoning
        // claim, so a request that needs reasoning is rejected for that reason only.
        val selected = config("gemini", "gemini-3.5-flash", baseUrl = "https://generativelanguage.googleapis.com/v1beta")
        assertTrue(resolver.resolveForRole(AgentRole.MAIN, selected).eligible)

        val needsReasoning = resolver.resolveForRole(
            AgentRole.MAIN,
            selected,
            ModelRequestRequirements(additionalCapabilities = setOf(ModelCapability.REASONING)),
        )
        assertFalse(needsReasoning.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, needsReasoning.eligibility.state)
        assertEquals(listOf(ModelCapability.REASONING), needsReasoning.eligibility.missingCapabilities)
    }

    // --- G. persistence / reload --------------------------------------------

    @Test
    fun `a discovered model is correctly represented after a reload`() = runBlocking {
        val store = InMemoryModelCatalogStore()
        val modelsUrl = { "https://generativelanguage.googleapis.com/v1beta/models" }
        val first = InMemoryModelCapabilityRegistry(initial = emptyList())
        RemoteModelCatalog(
            providerId = "gemini",
            modelsUrl = modelsUrl,
            store = store,
            capabilityRegistry = first,
            discovery = ModelDiscovery {
                ModelDiscoveryOutcome.Discovered(
                    models = listOf(DiscoveredModel(modelId = "gemini-9-experimental", displayName = "Gemini 9")),
                    reportedCount = 1,
                )
            },
        ).refresh(force = true)

        assertNotNull(first.get("gemini", "gemini-9-experimental"))

        // A restart: a fresh registry, the same store, no provider request.
        val second = InMemoryModelCapabilityRegistry(initial = emptyList())
        RemoteModelCatalog(
            providerId = "gemini",
            modelsUrl = modelsUrl,
            store = store,
            capabilityRegistry = second,
        ).restore()

        val restored = assertNotNull(second.get("gemini", "gemini-9-experimental"))
        assertEquals("Gemini 9", restored.displayName)
        // Discovery is identity, not capability: the reloaded model stays unknown
        // for tool calling and is not treated as supported.
        assertEquals(CapabilitySupport.UNKNOWN, restored.toolCalling)
        assertEquals(CapabilitySupport.UNKNOWN, restored.streaming)
        assertFalse(second.supports("gemini", "gemini-9-experimental", ModelCapability.TOOL_CALLING))
    }

    // --- H. a gateway model: the reported FreeLLMAPI failure -------------------

    /**
     * The reported diagnostic, reproduced and then resolved by the one statement
     * the connection can make about it.
     *
     * A model a gateway lists is registered as *discovered*: an identity with no
     * capability. `MAIN` requires tool calling and unknown is not support, so the
     * exact line the user saw is produced. Nothing about the discovery, the model
     * id or the role policy is wrong — the missing thing is the capability, and
     * the per-model declaration an endpoint-addressed connection can now save is
     * what supplies it.
     */
    @Test
    fun `a gateway model is ineligible for MAIN until its own connection states tool calling`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry(initial = emptyList())
        // What discovery registers for a gateway model: identity only.
        registry.register(ModelCapabilityProfile.discovered("freellmapi", "qwen3.6-27b"))

        val resolver = AgentModelResolver(capabilityRegistry = registry)
        val saved = config("freellmapi", "qwen3.6-27b", baseUrl = "https://gateway.example.dev/v1")

        // The reported failure, field for field.
        val rejected = resolver.resolveForRole(AgentRole.MAIN, saved)
        assertFalse(rejected.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, rejected.eligibility.state)
        assertEquals(CapabilitySupport.UNKNOWN, rejected.eligibility.profile.toolCalling)
        assertEquals(CapabilityProvenance.DISCOVERED, rejected.eligibility.profile.provenance)
        val error = assertNotNull(rejected.errorOrNull())
        assertEquals(AgentErrorCode.MODEL_NOT_ELIGIBLE, error.code)
        assertEquals("MODEL_CAPABILITY_UNKNOWN", error.details["cause"])
        assertEquals("toolCalling", error.details["capability"])
        assertEquals("UNKNOWN", error.details["support"])
        assertEquals("DISCOVERED", error.details["provenance"])
        assertTrue(
            error.message.contains("MODEL_NOT_ELIGIBLE role=MAIN provider=freellmapi model=qwen3.6-27b"),
            error.message,
        )
        // Neither the provider nor the id was rewritten while reaching the verdict.
        assertEquals("freellmapi", rejected.config.providerId)
        assertEquals("qwen3.6-27b", rejected.config.model)

        // The statement the Add/Edit form now saves for this connection. It travels
        // on the configuration, so it is this model — not the gateway — that is
        // stated tool-capable.
        val declared = resolver.resolveForRole(
            AgentRole.MAIN,
            saved.copy(declaredCapabilities = ModelCapabilityDeclaration.toolEnabledEndpoint()),
        )
        assertTrue(declared.eligible, declared.errorOrNull()?.message.orEmpty())
        assertEquals(ModelEligibilityState.AVAILABLE, declared.eligibility.state)
        assertEquals(CapabilitySupport.SUPPORTED, declared.eligibility.profile.toolCalling)
        assertEquals(CapabilityProvenance.HARDCODED, declared.eligibility.profile.provenance)
        assertEquals("qwen3.6-27b", declared.eligibility.modelId)

        // Guarantee: the declaration is per model. The registry entry was never
        // upgraded, and another model the same gateway lists keeps no capability.
        assertEquals(
            CapabilitySupport.UNKNOWN,
            assertNotNull(registry.get("freellmapi", "qwen3.6-27b")).toolCalling,
        )
        val other = resolver.resolveForRole(
            AgentRole.MAIN,
            config("freellmapi", "claude-unknown-model", baseUrl = "https://gateway.example.dev/v1"),
        )
        assertFalse(other.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, other.eligibility.state)
        assertEquals(CapabilitySupport.UNKNOWN, other.eligibility.profile.toolCalling)
    }

    /**
     * The registry path the runtime actually takes: the manager publishes a saved
     * declaration into the shared registry, and the role then resolves the saved
     * model without the declaration having to be repeated on every config.
     */
    @Test
    fun `a published gateway declaration makes the saved model eligible and no other`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry(initial = emptyList())
        val declaredModel = "qwen3.6-27b"

        // DefaultModelManager.registerPresetCapabilities, for a saved FreeLLMAPI preset.
        registry.registerOrUpdate(
            ModelCapabilityDeclaration.toolEnabledEndpoint()
                .applyTo(ModelCapabilityProfile.discovered("freellmapi", declaredModel)),
        )

        val resolver = AgentModelResolver(capabilityRegistry = registry)
        val eligible = resolver.resolveForRole(
            AgentRole.MAIN,
            config("freellmapi", declaredModel, baseUrl = "https://gateway.example.dev/v1"),
        )
        assertTrue(eligible.eligible, eligible.errorOrNull()?.message.orEmpty())
        assertEquals(CapabilitySupport.SUPPORTED, eligible.eligibility.profile.toolCalling)

        // A model the user did not state anything about is untouched.
        val untouched = resolver.resolveForRole(
            AgentRole.MAIN,
            config("freellmapi", "glm-5.3", baseUrl = "https://gateway.example.dev/v1"),
        )
        assertFalse(untouched.eligible)
        assertEquals(CapabilitySupport.UNKNOWN, untouched.eligibility.profile.toolCalling)
        // And the user's statement never made the gateway's whole provider capable.
        assertEquals(
            CapabilitySupport.UNKNOWN,
            registry.profile("freellmapi", "claude-unknown-model").toolCalling,
        )
    }
}
