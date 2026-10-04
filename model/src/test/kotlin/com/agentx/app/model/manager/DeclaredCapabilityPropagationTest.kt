package com.agentx.app.model.manager

import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.capability.CapabilitySupport
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityDeclaration
import com.agentx.app.model.capability.capabilityProfile
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
            customPreset(model = devstral).copy(
                declaredCapabilities = ModelCapabilityDeclaration.toolEnabledEndpoint(),
            ),
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
}
