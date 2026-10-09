package com.agentx.app.model.manager

import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.capability.capabilityProfile
import com.agentx.app.model.capability.statedCapabilitiesFor
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.runtime.EndpointSource
import com.agentx.app.model.runtime.ModelEndpoint
import com.agentx.app.model.runtime.RecordingLogSink
import com.agentx.app.model.runtime.customPreset
import com.agentx.app.model.runtime.recordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The connection the runtime actually hands to the eligibility checker is built
 * from the saved preset. If the declaration does not travel that far, the
 * selected model is reconstructed from `providerId` + `model` alone and loses the
 * capability that was stated for it — which is precisely how a configured model
 * could still reach the MAIN check as [CapabilitySupport.UNKNOWN].
 */
class DeclaredCapabilityPropagationTest {

    private val providerId = "openai-compatible"

    /** The exact id of the user's configured model; opaque and never rewritten. */
    private val devstral = "hf.co/unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q3_K_M"

    /** A custom id no built-in definition covers. */
    private val undefined = "hf.co/someone/undefined-local-model-GGUF:Q4_K_M"

    private val capabilities = InMemoryModelCapabilityRegistry(initial = emptyList())

    private val registry = GatewayModelConnectionRegistry(
        gateway = DefaultModelGateway(capabilities),
        providerFactory = { RecordingModelProvider() },
        logger = recordingLogger(RecordingLogSink()),
    )

    private fun connect(preset: ModelPreset): ModelConfig = registry.connect(
        preset = preset,
        endpoint = ModelEndpoint("http://127.0.0.1:8080", EndpointSource.CONFIGURED),
        credential = null,
    )

    @Test
    fun `a connected configuration carries the capability stated for its model`() {
        val config = connect(
            customPreset(model = devstral)
                .stating(devstral, ModelCapabilityDeclaration.toolEnabledEndpoint()),
        )

        assertEquals(devstral, config.model)
        assertEquals(providerId, config.providerId)
        assertEquals(ModelCapabilityDeclaration.toolEnabledEndpoint(), config.declaredCapabilities)

        val profile = config.capabilityProfile(capabilities)
        assertEquals(CapabilitySupport.SUPPORTED, profile.toolCalling)
        assertEquals(CapabilitySupport.SUPPORTED, profile.streaming)
    }

    @Test
    fun `a connection that states nothing carries no declaration`() {
        val config = connect(customPreset(model = undefined))

        assertNull(config.declaredCapabilities)
        assertNull(connect(customPreset(model = devstral)).declaredCapabilities)
    }

    @Test
    fun `an undeclared connected model still resolves to unknown`() {
        val config = connect(customPreset(model = undefined))

        assertEquals(CapabilitySupport.UNKNOWN, config.capabilityProfile(capabilities).toolCalling)
        assertEquals(
            CapabilitySupport.UNKNOWN,
            config.capabilityProfile(capabilities).support(ModelCapability.TOOL_CALLING),
        )
    }

    /**
     * A gateway serves several models and a role may be pointed at any of them, so a
     * connection that states several has to hand *all* of them to the runtime — each
     * keyed to the model it was made for. Otherwise the statement exists on the saved
     * preset and is still invisible to the eligibility check for every model except
     * the one the connection happens to name.
     */
    @Test
    fun `a connected configuration carries the statements for every model it serves`() {
        val declared = customPreset(model = devstral)
            .stating(devstral, ModelCapabilityDeclaration.toolEnabledEndpoint())
            .stating(undefined, ModelCapabilityDeclaration.toolEnabledEndpoint())

        val config = connect(declared)

        // The connection's own model keeps its statement on the single field...
        assertEquals(devstral, config.model)
        assertEquals(ModelCapabilityDeclaration.toolEnabledEndpoint(), config.declaredCapabilities)
        assertEquals(CapabilitySupport.SUPPORTED, config.capabilityProfile(capabilities).toolCalling)

        // ...and the other stated model travels beside it, keyed by its own id, so the
        // runtime can re-point at that model and keep the statement made for it.
        assertEquals(
            ModelCapabilityDeclaration.toolEnabledEndpoint(),
            config.statedCapabilitiesFor(undefined),
        )
        assertEquals(
            CapabilitySupport.SUPPORTED,
            config.copy(model = undefined).capabilityProfile(capabilities).toolCalling,
        )

        // A model nobody stated anything about resolves to unknown, whichever model
        // the configuration was last pointed at.
        val neverStated = "hf.co/someone/never-stated-GGUF:Q4_K_M"
        assertEquals(
            CapabilitySupport.UNKNOWN,
            config.copy(model = neverStated).capabilityProfile(capabilities).toolCalling,
        )
    }
}
