package com.agentx.app.model.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An explicit declaration is the only way a user-supplied model can stop being
 * [CapabilitySupport.UNKNOWN]. These cases pin down that it states exactly what
 * the user stated, for exactly the model it was stated for.
 */
class ModelCapabilityDeclarationTest {

    private val providerId = "openai-compatible"

    /** The opaque model id a local server reports; it must never be rewritten. */
    private val devstral = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M"

    /**
     * A registry with no definitions at all, so these cases isolate the
     * declaration mechanism from whatever the built-in catalog happens to know
     * about the ids used here.
     */
    private fun registry(): InMemoryModelCapabilityRegistry =
        InMemoryModelCapabilityRegistry(initial = emptyList())

    private fun InMemoryModelCapabilityRegistry.declare(
        modelId: String,
        declaration: ModelCapabilityDeclaration,
    ) {
        registerOrUpdate(declaration.applyTo(profile(providerId, modelId)))
    }

    @Test
    fun `an empty declaration states nothing and leaves a model unknown`() {
        val registry = registry()

        assertTrue(ModelCapabilityDeclaration.EMPTY.isEmpty)
        assertEquals(ModelCapabilityDeclaration.EMPTY.declared, emptyMap())
        assertEquals(
            CapabilitySupport.UNKNOWN,
            registry.support(providerId, devstral, ModelCapability.TOOL_CALLING),
        )
        assertEquals(
            CapabilitySupport.UNKNOWN,
            registry.support(providerId, devstral, ModelCapability.STREAMING),
        )
    }

    @Test
    fun `a declaration states only what it names and keeps the rest untouched`() {
        val declared = ModelCapabilityDeclaration.of(ModelCapability.TOOL_CALLING, CapabilitySupport.SUPPORTED)

        val profile = declared.applyTo(
            ModelCapabilityProfile.discovered(providerId, devstral, local = true)
                .copy(streaming = CapabilitySupport.SUPPORTED),
        )

        assertEquals(CapabilitySupport.SUPPORTED, profile.toolCalling)
        // Not named, so the value already established for this model survives.
        assertEquals(CapabilitySupport.SUPPORTED, profile.streaming)
        // Not named and never established, so it stays unknown rather than false.
        assertEquals(CapabilitySupport.UNKNOWN, profile.vision)
        assertEquals(CapabilitySupport.UNKNOWN, profile.structuredOutput)
        assertEquals(CapabilitySupport.UNKNOWN, profile.reasoning)
        assertTrue(profile.local)
    }

    @Test
    fun `a declaration is authoritative but only for its own model`() {
        val registry = registry()

        registry.declare(devstral, ModelCapabilityDeclaration.toolEnabledEndpoint())

        assertEquals(CapabilitySupport.SUPPORTED, registry.support(providerId, devstral, ModelCapability.TOOL_CALLING))
        assertEquals(CapabilitySupport.SUPPORTED, registry.support(providerId, devstral, ModelCapability.STREAMING))
        assertTrue(registry.isKnown(providerId, devstral))

        // A different model of the same provider is untouched: the declaration
        // belongs to the model, not to `openai-compatible`.
        assertEquals(
            CapabilitySupport.UNKNOWN,
            registry.support(providerId, "another-local-model", ModelCapability.TOOL_CALLING),
        )
        assertEquals(
            CapabilitySupport.UNKNOWN,
            registry.support(providerId, "llama-3.2-3b-instruct", ModelCapability.STREAMING),
        )
        assertFalse(registry.isKnown(providerId, "another-local-model"))
    }

    @Test
    fun `the declared model id is preserved exactly`() {
        val registry = registry()

        registry.declare(devstral, ModelCapabilityDeclaration.toolEnabledEndpoint())

        val profile = registry.get(providerId, devstral)
        assertEquals(devstral, profile?.modelId)
        assertEquals(devstral, registry.profile(providerId, devstral).modelId)
    }

    @Test
    fun `a later discovery or reconnect does not erase a declaration`() {
        val registry = registry()
        registry.declare(devstral, ModelCapabilityDeclaration.toolEnabledEndpoint())

        // What the connect flow registers after a model list or a reconnect: both
        // carry no capability information and must not downgrade the declaration.
        registry.registerOrUpdate(ModelCapabilityProfile.discovered(providerId, devstral, local = true))
        registry.registerOrUpdate(ModelCapabilityProfile.connected(providerId, devstral, local = true))

        assertEquals(CapabilitySupport.SUPPORTED, registry.support(providerId, devstral, ModelCapability.TOOL_CALLING))
        assertEquals(CapabilitySupport.SUPPORTED, registry.support(providerId, devstral, ModelCapability.STREAMING))
    }

    @Test
    fun `a declared unsupported capability is authoritative too`() {
        val declared = ModelCapabilityDeclaration.of(ModelCapability.TOOL_CALLING, CapabilitySupport.UNSUPPORTED)

        val profile = declared.applyTo(ModelCapabilityProfile.discovered(providerId, devstral))

        assertEquals(CapabilitySupport.UNSUPPORTED, profile.toolCalling)
        assertFalse(profile.supports(ModelCapability.TOOL_CALLING))
    }

    @Test
    fun `the tool-enabled statement claims tool calling and streaming only`() {
        val declared = ModelCapabilityDeclaration.toolEnabledEndpoint()

        assertEquals(
            mapOf(
                ModelCapability.TOOL_CALLING to CapabilitySupport.SUPPORTED,
                ModelCapability.STREAMING to CapabilitySupport.SUPPORTED,
            ),
            declared.declared,
        )
    }
}
