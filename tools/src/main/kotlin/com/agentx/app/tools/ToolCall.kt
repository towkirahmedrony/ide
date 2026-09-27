package com.agentx.app.tools

/**
 * A single tool invocation requested by the agent loop. The router is the only
 * component that turns this into an execution.
 */
data class ToolCall(
    val id: String,
    val toolId: ToolId,
    val input: ToolInput = ToolInput(),
) {
    init {
        require(id.isNotBlank()) { "Tool call id must not be blank" }
    }
}
