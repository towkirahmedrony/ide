package com.agentx.app.model

import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityErrors
import com.agentx.app.model.capability.ModelCapabilityRegistry

/**
 * Single entry point for model traffic. It resolves the provider named by
 * [ModelConfig.providerId], validates the request, and delegates. The agent
 * core depends only on this interface, never on a concrete provider.
 */
interface ModelGateway {
    fun register(provider: ModelProvider)

    /**
     * Registers [provider], replacing an existing provider with the same id.
     *
     * Model presets are switched by re-pointing the active connection, which
     * means the same provider id is registered again with a different endpoint.
     * Without this, connecting a second model would fail as a duplicate.
     */
    fun registerOrReplace(provider: ModelProvider) {
        unregister(provider.id)
        register(provider)
    }

    fun unregister(id: String): Boolean

    fun providers(): List<ModelProvider>

    fun provider(id: String): ModelProvider?

    /** Resolves the provider for [request], or null when none is registered. */
    fun resolve(request: ModelRequest): ModelProvider?

    /** Capabilities for [request]'s provider/model, honoring a config override. */
    fun capabilities(request: ModelRequest): ModelCapabilities

    suspend fun complete(request: ModelRequest): ModelResponse

    suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse
}

/** Default in-memory gateway. Registration order is preserved. */
class DefaultModelGateway(
    private val capabilityRegistry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry.DEFAULT,
) : ModelGateway {

    private val providers = LinkedHashMap<String, ModelProvider>()

    @Synchronized
    override fun register(provider: ModelProvider) {
        if (providers.containsKey(provider.id)) {
            throw ModelProviderError(
                code = ModelProviderErrorCode.DUPLICATE_PROVIDER,
                message = "A model provider with id '${provider.id}' is already registered",
                providerId = provider.id,
            )
        }
        providers[provider.id] = provider
    }

    @Synchronized
    override fun unregister(id: String): Boolean = providers.remove(id) != null

    @Synchronized
    override fun providers(): List<ModelProvider> = providers.values.toList()

    @Synchronized
    override fun provider(id: String): ModelProvider? = providers[id]

    @Synchronized
    override fun resolve(request: ModelRequest): ModelProvider? = providers[request.config.providerId]

    override fun capabilities(request: ModelRequest): ModelCapabilities {
        val provider = resolve(request) ?: throw providerNotFound(request)
        request.config.capabilities?.let { return it }
        val known = capabilityRegistry.get(request.config.providerId, request.config.model)
        if (known != null && known.known && known.enabled) return known.toModelCapabilities()
        // Unknown discovered models stay usable for chat. They never inherit a
        // provider-wide "tools are supported" default.
        return provider.capabilities(request.config.model).copy(toolCalling = false)
    }

    override suspend fun complete(request: ModelRequest): ModelResponse {
        validate(request)
        val provider = resolve(request) ?: throw providerNotFound(request)
        return ContentToolCallParser.normalize(provider.complete(request))
    }

    override suspend fun stream(
        request: ModelRequest,
        onEvent: (ModelStreamEvent) -> Unit,
    ): ModelResponse {
        validate(request)
        val provider = resolve(request) ?: throw providerNotFound(request)
        if (!capabilities(request).streaming) {
            throw ModelProviderError(
                code = ModelProviderErrorCode.UNSUPPORTED,
                message = "Provider '${provider.id}' does not support streaming",
                providerId = provider.id,
            )
        }
        return ContentToolCallParser.normalize(provider.stream(request, onEvent))
    }

    private fun validate(request: ModelRequest) {
        val errors = request.config.validate() + if (request.messages.isEmpty()) listOf("messages must not be empty") else emptyList()
        if (errors.isNotEmpty()) {
            throw ModelProviderError(
                code = ModelProviderErrorCode.INVALID_CONFIG,
                message = "Model request is invalid: ${errors.joinToString("; ")}",
                providerId = request.config.providerId,
                details = mapOf("errors" to errors),
            )
        }
        if (request.tools.isNotEmpty() && !capabilities(request).toolCalling) {
            val profile = capabilityRegistry.profile(request.config.providerId, request.config.model)
            throw ModelCapabilityErrors.unsupported(
                providerId = request.config.providerId,
                modelId = request.config.model,
                capability = ModelCapability.TOOL_CALLING,
                known = profile.known,
            )
        }
    }

    private fun providerNotFound(request: ModelRequest): ModelProviderError = ModelProviderError(
        code = ModelProviderErrorCode.PROVIDER_NOT_FOUND,
        message = "No model provider registered with id '${request.config.providerId}'",
        providerId = request.config.providerId,
    )
}
