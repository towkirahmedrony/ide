package com.agentx.app.model

import com.agentx.app.model.discovery.ModelDiscoveryOutcome

/** Metadata describing a model a provider exposes. */
data class ModelDescriptor(
    val id: String,
    val label: String,
    val providerId: String,
    val contextWindow: Int? = null,
    val capabilities: ModelCapabilities = ModelCapabilities(),
)

/**
 * Port for a concrete model backend. Local runtimes (Ollama, llama.cpp, vLLM)
 * and remote APIs (OpenAI, Anthropic, Google, OpenRouter, custom endpoints) all
 * implement this same interface; provider-specific HTTP handling stays here.
 *
 * The agent core must never reference a provider directly — everything goes
 * through [ModelGateway].
 */
interface ModelProvider {
    /** Stable identifier used by [ModelConfig.providerId]. */
    val id: String

    /** Capabilities reported for [modelId]. */
    fun capabilities(modelId: String): ModelCapabilities

    /**
     * Discovers the models this provider currently exposes for [config]'s
     * connection.
     *
     * Discovery reports; it never decides. A provider answers with what it can
     * list and nothing else: it does not assign a role, does not choose a model
     * and does not touch a saved configuration. The catalog layer owns
     * normalization, persistence and registration, and the Part 1 capability
     * registry owns capability knowledge.
     *
     * A provider with no model-list endpoint returns
     * [ModelDiscoveryOutcome.Unavailable] rather than an empty list, so a caller
     * can never mistake "no list exists" for "this provider has no models". The
     * default keeps that contract for any provider that does not implement
     * discovery.
     */
    suspend fun discoverModels(config: ModelConfig): ModelDiscoveryOutcome =
        ModelDiscoveryOutcome.Unavailable(
            reason = ModelDiscoveryOutcome.REASON_NO_MODEL_LIST,
            message = "The '$id' provider does not expose a model list.",
        )

    /**
     * The normalized descriptors [discoverModels] reports, for callers that only
     * need the ids. Empty when discovery is unavailable or failed, which keeps
     * the previous "no discovery, no list" behaviour without hiding the cause:
     * callers that must react to a failure use [discoverModels].
     */
    suspend fun listModels(config: ModelConfig): List<ModelDescriptor> =
        (discoverModels(config) as? ModelDiscoveryOutcome.Discovered)
            ?.models
            ?.map { model -> model.toModelDescriptor(id) }
            .orEmpty()

    /** Produces a single, complete response. */
    suspend fun complete(request: ModelRequest): ModelResponse

    /**
     * Produces a streamed response, emitting [ModelStreamEvent]s as they arrive
     * and returning the aggregated final [ModelResponse].
     */
    suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse
}
