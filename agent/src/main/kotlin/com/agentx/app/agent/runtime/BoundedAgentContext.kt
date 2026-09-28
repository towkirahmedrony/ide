package com.agentx.app.agent.runtime

import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelRole
import com.agentx.app.model.ModelToolCall

/**
 * Deterministic, bounded conversation context for one agent run.
 *
 * Keeps the newest messages, always preserves the system instruction and the
 * original user task, truncates oversized entries (tool results in practice),
 * and records what was shortened so callers know what the model did not see.
 * The future Context Engine will replace the "drop oldest" strategy behind the
 * same shape; nothing else in the loop needs to change.
 */
class BoundedAgentContext(
    private val maxContextChars: Int = DEFAULT_MAX_CONTEXT_CHARS,
    private val maxToolResultChars: Int = DEFAULT_MAX_TOOL_RESULT_CHARS,
) {

    /** True when at least one entry was shortened to fit its per-message budget. */
    var truncatedMessageCount: Int = 0
        private set

    private val messages = mutableListOf<ModelMessage>()

    /** Seeds the context with the system instruction and the user task. */
    fun start(systemPrompt: String, userPrompt: String) {
        messages.clear()
        messages += ModelMessage.system(systemPrompt)
        messages += ModelMessage.user(userPrompt)
    }

    /** Restores a conversation saved from a paused run (e.g. permission wait). */
    fun restore(saved: List<ModelMessage>) {
        messages.clear()
        messages += saved
    }

    fun addAssistant(content: String, toolCalls: List<ModelToolCall> = emptyList()) {
        messages += ModelMessage.assistant(content, toolCalls)
    }

    fun addToolResult(callId: String, toolName: String, content: String) {
        // A tool result is only ever appended once per tool call: resuming a
        // paused run or a repeated model call must not duplicate context.
        if (callId.isNotBlank() && messages.any { it.role == ModelRole.TOOL && it.toolCallId == callId }) {
            return
        }
        messages += ModelMessage.tool(callId, fit(content), toolName)
    }

    fun messages(): List<ModelMessage> = messages.toList()

    /** Trims older middle messages so the total stays under [maxContextChars]. */
    fun bounded(): List<ModelMessage> {
        var total = messages.sumOf { it.content.length }
        while (total > maxContextChars && messages.size > 2) {
            // Index 0 = system, 1 = user task: both are always preserved. Drop
            // the oldest message after them and note the removal.
            messages.removeAt(2)
            total = messages.sumOf { it.content.length }
            truncatedMessageCount += 1
        }
        return messages.toList()
    }

    /** Per-message cap; oversized tool results are shortened, not dropped. */
    private fun fit(content: String): String =
        if (content.length <= maxToolResultChars) {
            content
        } else {
            truncatedMessageCount += 1
            content.take(maxToolResultChars) + "\n…[truncated]"
        }

    companion object {
        const val DEFAULT_MAX_CONTEXT_CHARS = 48_000
        const val DEFAULT_MAX_TOOL_RESULT_CHARS = 4_000
    }
}
