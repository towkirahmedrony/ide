package com.agentx.app.model

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

    /** Lists models the provider exposes; empty when discovery is unsupported. */
    suspend fun listModels(): List<ModelDescriptor> = emptyList()

    /** Produces a single, complete response. */
    suspend fun complete(request: ModelRequest): ModelResponse

    /**
     * Produces a streamed response, emitting [ModelStreamEvent]s as they arrive
     * and returning the aggregated final [ModelResponse].
     */
    suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit): ModelResponse
}
