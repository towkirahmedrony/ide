package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilityProvenance
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
 * The reported runtime failure and its fix, at the eligibility boundary.
 *
 * A custom model reaches the MAIN check as `toolCalling = UNKNOWN` unless
 * something establishes the capability for it: nothing is inferred from the
 * provider, and `UNKNOWN` is not support. These cases pin down both halves of
 * the contract — the configured Devstral model resolves `SUPPORTED`, and an
 * OpenAI-compatible model with no capability information stays `UNKNOWN` and
 * stays ineligible.
 */
class DeclaredCustomModelCapabilityTest {

    private val providerId = "openai-compatible"

    /** Exactly the id the endpoint reports; it is opaque and must survive unchanged. */
    private val devstral = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M"

    /** Another model on the same provider, with no capability information anywhere. */
    private val unrelated = "hf.co/someone/undefined-local-model-GGUF:Q4_K_M"

    private val active = config(model = "active-model", provider = "active")

    private fun config(model: String, provider: String = providerId) = ModelConfig(
        providerId = provider,
        baseUrl = "http://127.0.0.1:8080/v1",
        model = model,
    )

    /** MAIN is explicitly assigned the custom endpoint, as a user would set it. */
    private fun preferencesFor(model: String) = AgentModelPreferences()
        .with(AgentRole.MAIN, RoleModelPreference(providerId, model))

    /**
     * The whole point: the configuration the resolver is handed is the one the
     * connection flow builds, so a capability stated for the model travels on it.
     */
    private fun resolver(
        model: String,
        registry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry(),
        declared: ModelCapabilityDeclaration? = null,
    ) = AgentModelResolver(
        preferences = preferencesFor(model),
        connections = { mapOf(providerId to config(model).copy(declaredCapabilities = declared)) },
        capabilityRegistry = registry,
    )

    /** The step the manager performs when it loads a saved preset. */
    private fun InMemoryModelCapabilityRegistry.publish(
        declaration: ModelCapabilityDeclaration,
        model: String = devstral,
    ) {
        registerOrUpdate(declaration.applyTo(profile(providerId, model)))
    }

    // --- the reported failure -------------------------------------------------

    @Test
    fun `the reported failure - a configured Devstral model resolves tool calling and passes MAIN`() =
        runBlocking {
            val result = resolver(devstral).resolveForRole(AgentRole.MAIN, active)

            assertTrue(result.eligible, result.eligibility.reason.orEmpty())
            assertEquals(ModelEligibilityState.AVAILABLE, result.eligibility.state)
            assertEquals(CapabilitySupport.SUPPORTED, result.eligibility.profile.toolCalling)
            assertEquals(CapabilitySupport.SUPPORTED, result.eligibility.profile.streaming)
            assertEquals(CapabilityProvenance.HARDCODED, result.eligibility.profile.provenance)
            assertNull(result.errorOrNull())
            // The id is never rewritten on the way to the check.
            assertEquals(devstral, result.config.model)
            assertEquals(devstral, result.eligibility.modelId)
            assertEquals(providerId, result.config.providerId)
        }

    @Test
    fun `a configuration that explicitly states tool calling passes MAIN`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry(initial = emptyList())

        val result = resolver(
            model = unrelated,
            registry = registry,
            declared = ModelCapabilityDeclaration.toolEnabledEndpoint(),
        ).resolveForRole(AgentRole.MAIN, active)

        assertTrue(result.eligible)
        assertEquals(CapabilitySupport.SUPPORTED, result.eligibility.profile.toolCalling)
        assertEquals(CapabilityProvenance.HARDCODED, result.eligibility.profile.provenance)
        assertEquals(unrelated, result.config.model)
    }

    // --- the guarantee that nothing was weakened -------------------------------

    @Test
    fun `an unrelated OpenAI-compatible model with no information stays UNKNOWN and ineligible`() =
        runBlocking {
            val result = resolver(unrelated).resolveForRole(AgentRole.MAIN, active)

            assertFalse(result.eligible)
            assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
            assertEquals(CapabilitySupport.UNKNOWN, result.eligibility.profile.toolCalling)
            assertFalse(result.eligibility.profile.known)

            // The rejection carries the resolved values, so this is diagnosable
            // without guessing and without logging anything secret.
            val error = assertNotNull(result.errorOrNull())
            assertEquals(AgentErrorCode.MODEL_NOT_ELIGIBLE, error.code)
            assertEquals("UNKNOWN", error.details["support"])
            assertEquals("false", error.details["known"])
            assertEquals("toolCalling", error.details["capability"])
            assertEquals(unrelated, error.details["model"])
            assertTrue(error.message.contains("reason=UNKNOWN"), error.message)
            assertTrue(error.message.contains("capability=toolCalling"), error.message)
            assertTrue(error.message.contains("support=UNKNOWN"), error.message)
            assertTrue(error.message.contains("model=$unrelated"), error.message)
        }

    @Test
    fun `an undeclared model does not inherit an OpenAI-compatible provider default`() = runBlocking {
        // The provider's own default advertises tool calling; an undeclared model
        // must not inherit it. Only the capability profile decides.
        val result = resolver(unrelated, registry = InMemoryModelCapabilityRegistry(initial = emptyList()))
            .resolveForRole(AgentRole.MAIN, active)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
        assertFalse(result.eligibility.profile.supports(ModelCapability.TOOL_CALLING))
    }

    @Test
    fun `a statement about one model never becomes a provider wide default`() = runBlocking {
        val registry = InMemoryModelCapabilityRegistry(initial = emptyList())
        registry.publish(ModelCapabilityDeclaration.toolEnabledEndpoint())

        val other = resolver(unrelated, registry).resolveForRole(AgentRole.MAIN, active)
        assertFalse(other.eligible)
        assertEquals(ModelEligibilityState.UNKNOWN, other.eligibility.state)
        assertEquals(CapabilitySupport.UNKNOWN, other.eligibility.profile.toolCalling)
        assertEquals(providerId, other.config.providerId)

        val declared = resolver(devstral, registry).resolveForRole(AgentRole.MAIN, active)
        assertTrue(declared.eligible)
    }

    @Test
    fun `a capability is not carried across a model change`() = runBlocking {
        // The connection states tool calling for the Devstral model; the role is
        // pointed at a different model on the same provider.
        val result = AgentModelResolver(
            preferences = preferencesFor(unrelated),
            connections = {
                mapOf(
                    providerId to config(devstral).copy(
                        declaredCapabilities = ModelCapabilityDeclaration.toolEnabledEndpoint(),
                    ),
                )
            },
            capabilityRegistry = InMemoryModelCapabilityRegistry(initial = emptyList()),
        ).resolveForRole(AgentRole.MAIN, active)

        assertFalse(result.eligible)
        assertEquals(unrelated, result.config.model)
        assertNull(result.config.declaredCapabilities)
        assertEquals(ModelEligibilityState.UNKNOWN, result.eligibility.state)
    }

    @Test
    fun `a statement of no tool calling is reported as unsupported`() = runBlocking {
        val result = resolver(
            model = devstral,
            registry = InMemoryModelCapabilityRegistry(initial = emptyList()),
            declared = ModelCapabilityDeclaration
                .of(ModelCapability.TOOL_CALLING, CapabilitySupport.UNSUPPORTED)
                .with(ModelCapability.STREAMING, CapabilitySupport.SUPPORTED),
        ).resolveForRole(AgentRole.MAIN, active)

        assertFalse(result.eligible)
        assertEquals(ModelEligibilityState.CAPABILITY_UNSUPPORTED, result.eligibility.state)
        assertEquals(listOf(ModelCapability.TOOL_CALLING), result.eligibility.missingCapabilities)
        assertEquals(
            "UNSUPPORTED",
            assertNotNull(result.errorOrNull()).details["support"],
        )
    }

    // --- persistence ----------------------------------------------------------

    @Test
    fun `a configuration saved and reloaded still resolves its capability`() = runBlocking {
        // What the Add/Edit form and the connect flow save, then a fresh process.
        val reloaded = assertNotNull(
            ModelPresetCodec.decode(
                ModelPresetCodec.encode(preset(declared = ModelCapabilityDeclaration.toolEnabledEndpoint())),
            ),
        )

        assertEquals(devstral, reloaded.modelIdentifier)
        assertEquals(CapabilitySupport.SUPPORTED, reloaded.declaredCapabilities.toolCalling)

        val result = resolver(
            model = reloaded.modelIdentifier,
            registry = InMemoryModelCapabilityRegistry(initial = emptyList()),
            declared = reloaded.declaredCapabilities,
        ).resolveForRole(AgentRole.MAIN, active)

        assertTrue(result.eligible)
        assertEquals(devstral, result.config.model)
    }

    @Test
    fun `reloading a configuration that states nothing leaves the definition in effect`() = runBlocking {
        val reloaded = assertNotNull(ModelPresetCodec.decode(ModelPresetCodec.encode(preset())))

        assertTrue(reloaded.declaredCapabilities.isEmpty)

        // Withdrawing a statement must not erase what the built-in definition says
        // about this model: the definition is authoritative, the statement is an
        // addition on top of it.
        val result = resolver(
            model = reloaded.modelIdentifier,
            registry = InMemoryModelCapabilityRegistry(initial = emptyList()),
            declared = reloaded.declaredCapabilities,
        ).resolveForRole(AgentRole.MAIN, active)

        assertTrue(result.eligible)
        assertEquals(CapabilitySupport.SUPPORTED, result.eligibility.profile.toolCalling)
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
