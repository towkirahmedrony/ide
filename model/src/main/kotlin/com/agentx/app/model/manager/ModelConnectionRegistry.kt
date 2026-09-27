package com.agentx.app.model.manager

import com.agentx.app.core.logging.ForgeLogger
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelGateway
import com.agentx.app.model.ModelProvider
import com.agentx.app.model.http.HttpTransport
import com.agentx.app.model.http.UrlConnectionHttpTransport
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.provider.openai.OpenAiCompatibleProvider
import com.agentx.app.model.runtime.ModelEndpoint

/** Builds the provider instance that serves a preset's protocol. */
fun interface ModelProviderFactory {
    fun create(preset: ModelPreset): ModelProvider
}

/**
 * Default factory: the protocol decides the provider id and chat path, so the
 * agent side stays provider-agnostic and no vendor is baked in.
 */
class OpenAiCompatibleProviderFactory(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
) : ModelProviderFactory {

    override fun create(preset: ModelPreset): ModelProvider = OpenAiCompatibleProvider(
        id = preset.apiProtocol.providerId,
        transport = transport,
        chatPath = preset.apiProtocol.chatPath,
    )
}

/**
 * Turns a healthy preset endpoint into the active Model Gateway connection.
 *
 * This is the only place that knows how a preset maps onto [ModelConfig], and it
 * is deliberately the only bridge between "a model is online" and "the agent can
 * use it". Switching models is therefore a gateway concern; the Agent Core is
 * never touched.
 */
interface ModelConnectionRegistry {
    /** Registers/updates the provider and makes [preset] the active connection. */
    fun connect(preset: ModelPreset, endpoint: ModelEndpoint, credential: String?): ModelConfig

    /** Removes the active connection when it belongs to [presetId]. */
    fun disconnect(presetId: String)

    fun activeConfig(): ModelConfig?

    fun activePresetId(): String?

    fun activeEndpoint(): ModelEndpoint?
}

class GatewayModelConnectionRegistry(
    private val gateway: ModelGateway = DefaultModelGateway(),
    private val providerFactory: ModelProviderFactory = OpenAiCompatibleProviderFactory(),
    private val logger: ForgeLogger = ForgeLoggers.create(
        level = LogLevel.WARN,
        baseFields = mapOf("component" to "model-connection"),
    ),
) : ModelConnectionRegistry {

    private data class Active(
        val presetId: String,
        val config: ModelConfig,
        val endpoint: ModelEndpoint,
    )

    private var active: Active? = null

    override fun connect(preset: ModelPreset, endpoint: ModelEndpoint, credential: String?): ModelConfig {
        val baseUrl = endpoint.url.trimEnd('/') + preset.normalizedApiBasePath
        val config = ModelConfig(
            providerId = preset.apiProtocol.providerId,
            baseUrl = baseUrl,
            model = preset.modelIdentifier,
            apiKey = credential,
            stream = true,
            // Only non-secret bookkeeping travels in metadata: never the URL or key.
            metadata = mapOf(
                "modelPresetId" to preset.id,
                "modelPresetName" to preset.displayName,
                "providerType" to preset.providerType.name,
            ),
        )
        gateway.registerOrReplace(providerFactory.create(preset))
        active = Active(preset.id, config, endpoint)
        logger.info(
            "Active model connection updated",
            mapOf(
                "preset" to preset.id,
                "provider" to config.providerId,
                "model" to config.model,
                "endpointSource" to endpoint.source.name,
                "hasCredential" to (credential != null),
            ),
        )
        return config
    }

    override fun disconnect(presetId: String) {
        val current = active ?: return
        if (current.presetId != presetId) return
        gateway.unregister(current.config.providerId)
        active = null
        logger.info("Active model connection cleared", mapOf("preset" to presetId))
    }

    override fun activeConfig(): ModelConfig? = active?.config

    override fun activePresetId(): String? = active?.presetId

    override fun activeEndpoint(): ModelEndpoint? = active?.endpoint
}
