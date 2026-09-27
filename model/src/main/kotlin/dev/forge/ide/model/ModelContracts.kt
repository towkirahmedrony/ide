package dev.forge.ide.model

import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.architecture.LayerStatus

/** Role of a message in a model conversation. */
enum class ModelRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

/** A single conversation message. */
data class ModelMessage(
    val role: ModelRole,
    val content: String,
    val name: String? = null,
)

/**
 * A provider-agnostic model request. The model id is always supplied by the
 * caller; nothing about a specific provider is baked in.
 */
data class ModelRequest(
    val model: String,
    val messages: List<ModelMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

/** Metadata describing a model available through a provider. */
data class ModelDescriptor(
    val id: String,
    val label: String,
    val providerId: String,
    val contextWindow: Int? = null,
)

data class ModelResponse(
    val content: String,
    val model: String,
    val stopReason: String? = null,
)

/** Streaming event emitted while a model response is generated. */
sealed interface ModelStreamEvent {
    data class Text(val text: String) : ModelStreamEvent

    data object Completed : ModelStreamEvent
}

/**
 * Port for a concrete model backend. Both local runtimes and remote APIs
 * implement this; no implementation exists yet.
 */
interface ModelProvider {
    val id: String

    suspend fun listModels(): List<ModelDescriptor>

    suspend fun complete(request: ModelRequest): ModelResponse

    suspend fun stream(request: ModelRequest, onEvent: (ModelStreamEvent) -> Unit)
}

/** Resolves model ids to the provider that serves them. */
interface ModelGateway {
    fun register(provider: ModelProvider)

    fun providers(): List<ModelProvider>

    fun resolve(modelId: String): ModelProvider?
}

val MODEL_LAYER = LayerDescriptor(
    id = "model",
    title = "Model Gateway",
    summary = "Routes model requests to local runtimes or remote providers behind one interface.",
    status = LayerStatus.CONTRACT_ONLY,
)
