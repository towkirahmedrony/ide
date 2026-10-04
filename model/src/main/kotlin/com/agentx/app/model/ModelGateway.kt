package com.agentx.app.model

import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapability
import com.agentx.app.model.capability.ModelCapabilityErrors
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.capability.capabilityProfile
import com.agentx.app.model.capability.toCapabilityProfile

/**
 * Single entry point for model traffic. It resolves the connection named by
 * [ModelConfig.connectionId], validates the request, and delegates. The agent
 * core depends only on this interface, never on a concrete provider.
 *
 * Routing on the connection identity (not the provider family) is what lets two
 * independent connections of the same family — two custom OpenAI-compatible
 * endpoints, or a custom endpoint and Ollama — be registered and addressed at the
 * same time. [ModelConfig.providerId] still identifies the provider family and is
 * what capability, rate-limit and eligibility lookups use.
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

    /**
     * Registers [provider] under an explicit connection identity [connectionId].
     *
     * This is the registration the model connection registry uses: two
     * independent connections can share one provider family (`openai-compatible`),
     * so the family id is not a usable registration key. Replacing an existing
     * entry for [connectionId] is expected — a connection is re-pointed when its
     * preset changes — and never touches another connection.
     */
    fun registerConnection(connectionId: String, provider: ModelProvider) {
        unregister(connectionId)
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
    override fun registerConnection(connectionId: String, provider: ModelProvider) {
        require(connectionId.isNotBlank()) { "connectionId must not be blank" }
        // Keyed by the connection identity, and replaced in place: a re-pointed
        // connection updates its own entry and leaves every other one alone.
        providers[connectionId] = provider
    }

    @Synchronized
    override fun unregister(id: String): Boolean = providers.remove(id) != null

    @Synchronized
    override fun providers(): List<ModelProvider> = providers.values.toList()

    @Synchronized
    override fun provider(id: String): ModelProvider? = providers[id]

    @Synchronized
    override fun resolve(request: ModelRequest): ModelProvider? = providers[request.config.connectionId]

    override fun capabilities(request: ModelRequest): ModelCapabilities {
        val provider = resolve(request) ?: throw providerNotFound(request)
        request.config.capabilities?.let { return it }
        // The same precedence the eligibility checker uses, so a capability stated
        // for this configuration is honored when the request is actually made and
        // not only when it is admitted. An unstated, unregistered model still has
        // known = false and falls through to the conservative default below.
        val profile = request.config.capabilityProfile(capabilityRegistry)
        if (profile.known && profile.enabled) return profile.toModelCapabilities()
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
        rejectDisabled(request)
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

    /**
     * Refuses a request for a model whose authoritative definition is disabled.
     * An unknown/discovered model has no definition to disable, so it stays
     * usable for chat. This mirrors the agent resolver's eligibility DISABLED
     * state at the execution boundary, so an ineligible model cannot be run even
     * if it reaches the gateway directly.
     */
    private fun rejectDisabled(request: ModelRequest) {
        val profile = request.config.capabilities
            ?.toCapabilityProfile(request.config.providerId, request.config.model)
            ?: capabilityRegistry.get(request.config.providerId, request.config.model)
            ?: return
        if (profile.enabled) return
        throw ModelProviderError(
            code = ModelProviderErrorCode.UNSUPPORTED,
            message = "${ModelCapabilityErrors.MODEL_DISABLED} provider=${request.config.providerId} " +
                "model=${request.config.model}",
            providerId = request.config.providerId,
            providerErrorType = ModelCapabilityErrors.MODEL_DISABLED,
            details = mapOf(
                "model" to request.config.model,
                "known" to profile.known.toString(),
            ),
        )
    }

    private fun providerNotFound(request: ModelRequest): ModelProviderError = ModelProviderError(
        code = ModelProviderErrorCode.PROVIDER_NOT_FOUND,
        message = "No model connection registered with id '${request.config.connectionId}'",
        providerId = request.config.providerId,
        details = mapOf("connectionId" to request.config.connectionId),
    )
}
