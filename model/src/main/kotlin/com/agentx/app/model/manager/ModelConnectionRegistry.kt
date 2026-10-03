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
import com.agentx.app.model.preset.ModelApiProtocol
import com.agentx.app.model.preset.ModelPreset
import com.agentx.app.model.provider.gemini.GeminiModelProvider
import com.agentx.app.model.provider.openai.OpenAiCompatibleProvider
import com.agentx.app.model.runtime.ModelEndpoint

/** Builds the provider instance that serves a preset's protocol. */
fun interface ModelProviderFactory {
    fun create(preset: ModelPreset): ModelProvider
}

/**
 * Default factory: the preset's protocol decides which provider speaks to it, and
 * [ModelPreset.providerId] decides the provider identity, so Gemini, Groq and a
 * local OpenAI-compatible endpoint stay distinct provider instances that never
 * overwrite one another.
 *
 * Gemini's own API is not the OpenAI-compatible protocol: it addresses
 * `models/<model>:generateContent` with Gemini's key header, so it gets its own
 * provider. Groq, a local server and every other compatible endpoint keep the
 * OpenAI-compatible provider and its `<base>/chat/completions` path, unchanged.
 */
class DefaultModelProviderFactory(
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    /**
     * Optional structured Developer Log sink. Passing the connection registry's
     * logger here is what gives every normal completion the same
     * `[GEMINI|GROQ|…][COMPLETION]` trail as discovery and connect.
     */
    private val logger: ForgeLogger? = null,
) : ModelProviderFactory {

    override fun create(preset: ModelPreset): ModelProvider = when (preset.apiProtocol) {
        // Gemini's own API: the model travels in the path and the key in Gemini's
        // header, so it is a different protocol rather than a compatible endpoint.
        ModelApiProtocol.GEMINI_NATIVE -> GeminiModelProvider(
            id = preset.providerId,
            transport = transport,
            logger = logger,
        )

        ModelApiProtocol.OPENAI_COMPATIBLE,
        ModelApiProtocol.OLLAMA,
        -> OpenAiCompatibleProvider(
            id = preset.providerId,
            transport = transport,
            chatPath = preset.apiProtocol.chatPath,
            logger = logger,
        )
    }
}

/**
 * Turns healthy preset endpoints into Model Gateway connections.
 *
 * This is the only place that knows how a preset maps onto [ModelConfig]. It
 * keeps *every* connected provider at once, keyed by provider identity, so a
 * role can resolve Gemini while another resolves Groq and another resolves a
 * local OpenAI-compatible model. Connecting one provider never removes another.
 */
interface ModelConnectionRegistry {
    /** Registers the preset's provider and makes that connection the active one. */
    fun connect(preset: ModelPreset, endpoint: ModelEndpoint, credential: String?): ModelConfig

    /**
     * Removes the connection that belongs to [presetId], whether or not it is the
     * active one, and unregisters only that provider. Returns false when nothing
     * was connected for the preset.
     */
    fun disconnect(presetId: String): Boolean

    fun activeConfig(): ModelConfig?

    fun activePresetId(): String?

    fun activeEndpoint(): ModelEndpoint?

    /** Every connected provider configuration, keyed by its provider identity. */
    fun connections(): Map<String, ModelConfig>

    /** The configuration of one connected provider, or null when it is not connected. */
    fun connection(providerId: String): ModelConfig?

    /** Whether [presetId] currently holds a connection. */
    fun isConnected(presetId: String): Boolean
}

class GatewayModelConnectionRegistry(
    private val gateway: ModelGateway = DefaultModelGateway(),
    private val providerFactory: ModelProviderFactory = DefaultModelProviderFactory(),
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

    /**
     * One entry per provider identity. Two presets that share an identity (for
     * example two plain OpenAI-compatible servers) replace each other, while a
     * different identity is untouched. There is no global "current provider":
     * traffic is routed per request from [ModelConfig.providerId].
     */
    private val connected = LinkedHashMap<String, Active>()

    /** Identity of the most recently connected provider; the manager's active one. */
    private var activeProviderId: String? = null

    @Synchronized
    override fun connect(preset: ModelPreset, endpoint: ModelEndpoint, credential: String?): ModelConfig {
        val provider = providerFactory.create(preset)
        val baseUrl = endpoint.url.trimEnd('/') + preset.normalizedApiBasePath
        val config = ModelConfig(
            providerId = provider.id,
            baseUrl = baseUrl,
            model = preset.modelIdentifier,
            apiKey = credential,
            stream = true,
            // Carried by the connection, not by each call: normal completions and
            // the catalog's model list go through this same config.
            headers = preset.requestHeaders,
            // Only non-secret bookkeeping travels in metadata: never the URL or key.
            metadata = mapOf(
                "modelPresetId" to preset.id,
                "modelPresetName" to preset.displayName,
                "providerType" to preset.providerType.name,
            ),
        )
        gateway.registerOrReplace(provider)
        connected[provider.id] = Active(preset.id, config, endpoint)
        activeProviderId = provider.id
        logger.info(
            "Active model connection updated",
            mapOf(
                "preset" to preset.id,
                "provider" to config.providerId,
                "model" to config.model,
                "endpointSource" to endpoint.source.name,
                "hasCredential" to (credential != null),
                "connections" to connected.size,
            ),
        )
        return config
    }

    @Synchronized
    override fun disconnect(presetId: String): Boolean {
        val key = connected.entries.firstOrNull { it.value.presetId == presetId }?.key ?: return false
        connected.remove(key)
        if (activeProviderId == key) activeProviderId = null
        gateway.unregister(key)
        logger.info(
            "Model connection cleared",
            mapOf("preset" to presetId, "provider" to key, "connections" to connected.size),
        )
        return true
    }

    @Synchronized
    override fun activeConfig(): ModelConfig? = activeProviderId?.let { connected[it]?.config }

    @Synchronized
    override fun activePresetId(): String? = activeProviderId?.let { connected[it]?.presetId }

    @Synchronized
    override fun activeEndpoint(): ModelEndpoint? = activeProviderId?.let { connected[it]?.endpoint }

    @Synchronized
    override fun connections(): Map<String, ModelConfig> = connected.mapValues { (_, active) -> active.config }

    @Synchronized
    override fun connection(providerId: String): ModelConfig? = connected[providerId]?.config

    @Synchronized
    override fun isConnected(presetId: String): Boolean =
        connected.values.any { it.presetId == presetId }
}
