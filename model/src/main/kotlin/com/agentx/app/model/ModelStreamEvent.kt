package com.agentx.app.model

/**
 * Incremental events emitted while a streamed response is produced. Consumers
 * (the future agent core) render [TextDelta] as it arrives and use
 * [Completed] for the final state. [ToolCallDelta] exists so streaming can
 * support tool calling later without changing this contract.
 */
sealed interface ModelStreamEvent {
    data class Started(val model: String, val providerId: String) : ModelStreamEvent

    data class TextDelta(val text: String) : ModelStreamEvent

    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String?,
    ) : ModelStreamEvent

    data class UsageReported(val usage: ModelUsage) : ModelStreamEvent

    data class Completed(
        val finishReason: ModelFinishReason?,
        val usage: ModelUsage?,
    ) : ModelStreamEvent
}
