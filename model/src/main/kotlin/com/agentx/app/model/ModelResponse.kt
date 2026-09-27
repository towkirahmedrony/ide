package com.agentx.app.model

/** Why a model stopped generating. */
enum class ModelFinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CONTENT_FILTER,
    ERROR,
    UNKNOWN,
}

/** Token accounting reported by a provider, when available. */
data class ModelUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)

/**
 * Normalized, provider-agnostic model response. Both [complete] and [stream]
 * return this shape so callers never deal with provider payloads.
 */
data class ModelResponse(
    val model: String,
    val providerId: String,
    val content: String,
    val toolCalls: List<ModelToolCall> = emptyList(),
    val finishReason: ModelFinishReason? = null,
    val usage: ModelUsage? = null,
)
