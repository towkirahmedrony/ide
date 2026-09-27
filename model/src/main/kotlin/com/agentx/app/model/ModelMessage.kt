package com.agentx.app.model

import com.agentx.app.model.json.JsonObject

/** Role of a message in a model conversation. */
enum class ModelRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL;

    /** Wire representation expected by OpenAI-style APIs. */
    val wireName: String get() = name.lowercase()
}

/**
 * A tool invocation requested by a model. Arguments stay structured so the
 * tool system can consume them directly. Tool calling is not executed yet; this
 * type only makes the model abstraction capable of carrying it.
 */
data class ModelToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject = emptyMap(),
)

/**
 * A single conversation message. Content is always a string here; richer
 * multimodal content can be added later without changing the provider contract.
 */
data class ModelMessage(
    val role: ModelRole,
    val content: String,
    val name: String? = null,
    /** Set for [ModelRole.TOOL] messages, linking back to a [ModelToolCall]. */
    val toolCallId: String? = null,
    /** Set for assistant messages that request tool calls. */
    val toolCalls: List<ModelToolCall> = emptyList(),
) {
    companion object {
        fun system(content: String): ModelMessage = ModelMessage(ModelRole.SYSTEM, content)

        fun user(content: String): ModelMessage = ModelMessage(ModelRole.USER, content)

        fun assistant(
            content: String,
            toolCalls: List<ModelToolCall> = emptyList(),
        ): ModelMessage = ModelMessage(ModelRole.ASSISTANT, content, toolCalls = toolCalls)

        fun tool(toolCallId: String, content: String, name: String? = null): ModelMessage =
            ModelMessage(ModelRole.TOOL, content, name = name, toolCallId = toolCallId)
    }
}
