package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.preset.ModelPresetCodec
import com.agentx.app.model.preset.ModelProviderType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reported failure and its fix, end to end at the eligibility boundary.
 *
 * A user-supplied model that AgentX cannot identify stays
 * [CapabilitySupport.UNKNOWN], and MAIN requires tool calling, so it is correctly
 * rejected. What was wrong is that there was no way for the user to say that
 * their model *does* call tools. These cases cover both halves: an undeclared
 * custom model is still refused (unknown is not support), and a model the user
 * declared resolves as supported — for that model alone.
 */
class DeclaredCustomModelCapabilityTest {

    private val providerId = "openai-compatible"

    /** Exactly the id the endpoint reports; it is opaque and must survive unchanged. */
    private val devstral = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M"

    private val otherModel = "some-other-local-model"

    private val active = config(model = "active-model", provider = "active")

    private fun config(model: String, provider: String = providerId) = ModelConfig(
        providerId = provider,
        baseUrl = "http://127.0.0.1:8080/v1",
        model = model,
    )

    /** MAIN is explicitly assigned the custom endpoint, as a user would set it. */
    private fun preferencesFor(model: String) = AgentModelPreferences()
        .with(AgentRole.MAIN, RoleModelPreference(providerId, model))

    private fun resolver(
        model: String,
        registry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry(),
    ) = AgentModelResolver(
        preferences = preferencesFor(model),
        connections = { mapOf(providerId to config(model)) },
        capabilityRegistry = registry,
    )

    /** The step the manager performs when it loads a saved preset. */
    private fun InMemoryModelCapabilityRegistry.publish(
        declaration: ModelCapabilityDeclaration,
        model: String = devstral,
    ) {
        registerOrUpdate(declaration.applyTo(profile(providerId, model)))
    }

    @Test
    fun `an undeclared custom model is still refused for MAIN`() = runBlocking {
        val result = resolver(devstral).resolveForRole(AgentRole.MAIN, active)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
        assertEquals(CapabilitySupport.UNKNOWN, result.eligibility.profile.toolCalling)
        val error = assertNotNull(result.errorOrNull())
        assertEquals(AgentErrorCode.MODEL_NOT_ELIGIBLE, error.code)
        assertTrue(error.message.contains("reason=UNKNOWN"), error.message)
        assertTrue(error.message.contains("capability=toolCalling"), error.message)
        // The reported failure, verbatim: the model id is never rewritten.
        assertTrue(error.message.contains("model=$devstral"), error.message)
    }

    @Test
    fun `a declared custom model satisfies MAIN`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry()
        registry.publish(ModelCapabilityDeclaration.toolEnabledEndpoint())

        val result = resolver(devstral, registry).resolveForRole(AgentRole.MAIN, active)

        assertTrue(result.eligible)
        assertEquals(ModelEligibilityState.AVAILABLE, result.eligibility.state)
        assertEquals(CapabilitySupport.SUPPORTED, result.eligibility.profile.toolCalling)
        assertTrue(result.eligibility.profile.supports(ModelCapability.TOOL_CALLING))
        assertTrue(result.eligibility.profile.supports(ModelCapability.STREAMING))
        assertNull(result.errorOrNull())
        assertEquals(result.config, result.eligibleConfigOrThrow())
    }

    @Test
    fun `a resolved declared model keeps its exact model id`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry()
        registry.publish(ModelCapabilityDeclaration.toolEnabledEndpoint())

        val result = resolver(devstral, registry).resolveForRole(AgentRole.MAIN, active)

        assertEquals(devstral, result.config.model)
        assertEquals(devstral, result.eligibility.modelId)
        assertEquals(devstral, result.eligibility.profile.modelId)
        assertEquals(providerId, result.config.providerId)
    }

    @Test
    fun `a declaration never becomes a provider wide default`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry()
        registry.publish(ModelCapabilityDeclaration.toolEnabledEndpoint())

        // Another model on the same OpenAI-compatible provider was not declared, so
        // it stays unknown and MAIN still refuses it.
        val other = resolver(otherModel, registry).resolveForRole(AgentRole.MAIN, active)

        assertFalse(other.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, other.eligibility.state)
        assertEquals(CapabilitySupport.UNKNOWN, other.eligibility.profile.toolCalling)
        assertEquals(providerId, other.config.providerId)

        // ...while the declared model on the very same provider is eligible.
        val declared = resolver(devstral, registry).resolveForRole(AgentRole.MAIN, active)
        assertTrue(declared.eligible)
    }

    @Test
    fun `unknown still outranks a provider that advertises tools`() = runBlocking {
        // The OpenAI-compatible provider's own default advertises tool calling; an
        // undeclared model must not inherit it. Only the registry profile counts.
        val result = resolver(otherModel).resolveForRole(AgentRole.MAIN, active)

        assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
        assertFalse(result.eligibility.profile.supports(ModelCapability.TOOL_CALLING))
        assertFalse(result.eligible)
    }

    @Test
    fun `a declared unsupported capability is reported as unsupported`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry()
        registry.publish(
            ModelCapabilityDeclaration
                .of(ModelCapability.TOOL_CALLING, CapabilitySupport.UNSUPPORTED)
                .with(ModelCapability.STREAMING, CapabilitySupport.SUPPORTED),
        )

        val result = resolver(devstral, registry).resolveForRole(AgentRole.MAIN, active)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.CAPABILITY_UNSUPPORTED, result.eligibility.state)
        assertEquals(listOf(ModelCapability.TOOL_CALLING), result.eligibility.missingCapabilities)
    }

    @Test
    fun `a declaration saved with the preset still resolves after a reload`() = runBlocking {
        // Persist exactly what the Add/Edit form and the connect flow would save.
        val saved = preset(declared = ModelCapabilityDeclaration.toolEnabledEndpoint())
        val stored = ModelPresetCodec.encode(saved)

        // A fresh process: the preset is decoded and its declaration published.
        val reloaded = assertNotNull(ModelPresetCodec.decode(stored))
        assertEquals(devstral, reloaded.modelIdentifier)
        assertEquals(CapabilitySupport.SUPPORTED, reloaded.declaredCapabilities.toolCalling)

        val registry = InMemoryModelCapabilityRegistry()
        registry.publish(reloaded.declaredCapabilities, model = reloaded.modelIdentifier)

        val result = resolver(devstral, registry).resolveForRole(AgentRole.MAIN, active)
        assertTrue(result.eligible)
        assertEquals(devstral, result.config.model)
    }

    @Test
    fun `a preset that declares nothing reloads as unknown`() = runBlocking {
        val reloaded = assertNotNull(ModelPresetCodec.decode(ModelPresetCodec.encode(preset())))

        assertTrue(reloaded.declaredCapabilities.isEmpty)

        val registry = InMemoryModelCapabilityRegistry()
        registry.publish(reloaded.declaredCapabilities, model = reloaded.modelIdentifier)

        val result = resolver(devstral, registry).resolveForRole(AgentRole.MAIN, active)
        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
    }

    private fun preset(declared: ModelCapabilityDeclaration = ModelCapabilityDeclaration.EMPTY) = ModelPreset(
        id = "custom-devstral",
        displayName = "Devstral",
        providerType = ModelProviderType.REMOTE_OPENAI_COMPATIBLE,
        modelIdentifier = devstral,
        apiProtocol = ModelApiProtocol.OPENAI_COMPATIBLE,
        apiBasePath = "/v1",
        declaredCapabilities = declared,
        setupKind = "custom",
    )
}
