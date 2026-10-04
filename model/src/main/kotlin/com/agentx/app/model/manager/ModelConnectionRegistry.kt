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
 * the preset's connection identity ([ModelPreset.connectionId]) becomes the
 * provider instance id, so Gemini, Groq and every independent custom/local
 * endpoint stay distinct provider instances that never overwrite one another —
 * including two connections that share one protocol.
 *
 * Gemini's own API is not the OpenAI-compatible protocol: it addresses
 * `models/<model>:generateContent` with Gemini's key header, so it gets its own
 * provider. Groq, a local server and every other compatible endpoint keep the
 * OpenAI-compatible provider and its `<base>/chat/completions` path, unchanged.
 *
 * The registry registers whatever this returns under the connection identity
 * (never under the provider's own id), so an injected factory that reports a
 * shared provider-family id (`openai-compatible`) cannot collapse two
 * connections onto one registration.
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
            // The connection identity, not the provider family: two Gemini presets
            // are two independently addressable connections.
            id = preset.connectionId,
            transport = transport,
            logger = logger,
        )

        ModelApiProtocol.OPENAI_COMPATIBLE,
        ModelApiProtocol.OLLAMA,
        -> OpenAiCompatibleProvider(
            id = preset.connectionId,
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
 * keeps *every* connected connection at once, keyed by the persisted connection
 * identity ([ModelPreset.connectionId]) — not by provider family — so a role can
 * resolve Gemini while another resolves Groq, a third resolves a local
 * OpenAI-compatible endpoint and a fourth resolves an Ollama server, even when two
 * of them share one protocol. Connecting one connection never removes another.
 */
interface ModelConnectionRegistry {
    /** Registers the preset's provider and makes that connection the active one. */
    fun connect(preset: ModelPreset, endpoint: ModelEndpoint, credential: String?): ModelConfig

    /**
     * Removes the connection that belongs to [presetId], whether or not it is the
     * active one, and unregisters only that connection. Returns false when nothing
     * was connected for the preset. Only that preset's connection is touched.
     */
    fun disconnect(presetId: String): Boolean

    fun activeConfig(): ModelConfig?

    fun activePresetId(): String?

    fun activeEndpoint(): ModelEndpoint?

    /**
     * Every connected configuration, keyed by its connection identity
     * ([ModelPreset.connectionId]), so two connections of the same provider family
     * keep separate entries.
     */
    fun connections(): Map<String, ModelConfig>

    /** The configuration of one connection, or null when it is not connected. */
    fun connection(connectionId: String): ModelConfig?

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
     * One entry per persisted connection identity ([ModelPreset.connectionId]).
     * Two presets are independent connections, even when they share a provider
     * family (two plain OpenAI-compatible servers, or a custom endpoint and
     * Ollama), so neither replaces the other. There is no global "current
     * provider": traffic is routed per request from [ModelConfig.connectionId].
     */
    private val connected = LinkedHashMap<String, Active>()

    /** Connection identity of the most recently connected entry; the manager's active one. */
    private var activeConnectionId: String? = null

    @Synchronized
    override fun connect(preset: ModelPreset, endpoint: ModelEndpoint, credential: String?): ModelConfig {
        val connectionId = preset.connectionId
        // The gateway registration is keyed by this connection id, so a factory
        // that reports a shared provider-family id (openai-compatible) can never
        // overwrite an unrelated connection of the same family.
        val provider = providerFactory.create(preset)
        val baseUrl = endpoint.url.trimEnd('/') + preset.normalizedApiBasePath
        val config = ModelConfig(
            // The provider family stays on providerId (capability/eligibility/
            // rate-limit lookups), while connectionId addresses this instance.
            providerId = preset.providerId,
            connectionId = connectionId,
            baseUrl = baseUrl,
            model = preset.modelIdentifier,
            apiKey = credential,
            stream = true,
            // Carried by the connection, not by each call: normal completions and
            // the catalog's model list go through this same config.
            headers = preset.requestHeaders,
            // What the saved configuration states about this model travels with the
            // model it was stated for, so the runtime config is not reconstructed
            // from providerId + model alone and does not lose the capability on the
            // way to the eligibility check.
            declaredCapabilities = preset.declaredCapabilities.takeUnless { it.isEmpty },
            // Only non-secret bookkeeping travels in metadata: never the URL or key.
            metadata = mapOf(
                "modelPresetId" to preset.id,
                "modelPresetName" to preset.displayName,
                "providerType" to preset.providerType.name,
            ),
        )
        gateway.registerConnection(connectionId, provider)
        connected[connectionId] = Active(preset.id, config, endpoint)
        activeConnectionId = connectionId
        logger.info(
            "Active model connection updated",
            mapOf(
                "preset" to preset.id,
                // Safe identifiers only: the internal connection id, the provider
                // family and the non-secret model id. Never the endpoint or key.
                "connection" to connectionId,
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
        // The connection identity is the preset id, so this removes exactly that
        // preset's connection and nothing else. The lookup also tolerates an entry
        // whose key is its preset id, which is how the registry always stores it.
        val key = connected.entries
            .firstOrNull { it.key == presetId || it.value.presetId == presetId }
            ?.key ?: return false
        connected.remove(key)
        if (activeConnectionId == key) activeConnectionId = null
        gateway.unregister(key)
        logger.info(
            "Model connection cleared",
            mapOf("preset" to presetId, "connection" to key, "connections" to connected.size),
        )
        return true
    }

    @Synchronized
    override fun activeConfig(): ModelConfig? = activeConnectionId?.let { connected[it]?.config }

    @Synchronized
    override fun activePresetId(): String? = activeConnectionId?.let { connected[it]?.presetId }

    @Synchronized
    override fun activeEndpoint(): ModelEndpoint? = activeConnectionId?.let { connected[it]?.endpoint }

    @Synchronized
    override fun connections(): Map<String, ModelConfig> = connected.mapValues { (_, active) -> active.config }

    @Synchronized
    override fun connection(connectionId: String): ModelConfig? = connected[connectionId]?.config

    @Synchronized
    override fun isConnected(presetId: String): Boolean =
        connected.values.any { it.presetId == presetId }
}
