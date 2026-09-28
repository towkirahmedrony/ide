package com.agentx.app.context

import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelRole
import com.agentx.app.model.ModelToolCall

/**
 * Conversation and tool-result context for one agent run, backed by the
 * [ContextEngine].
 *
 * Differences from a naive "drop the oldest message" policy:
 * - an assistant message that requested tool calls is always dropped together
 *   with its tool results, so the model never receives an orphan tool result;
 * - tool results are bounded to the tool-result budget before they are handed
 *   back (redaction happens once, in the Tool System executor);
 * - every tool result is recorded as a [ContextItem], so the context the run
 *   built can be inspected afterwards;
 * - the system instruction and the original task are never dropped.
 */
class DefaultRunContext(
    private val engine: ContextEngine,
    override val sessionId: String,
    private val budget: ContextBudget,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RunContext {

    private val messages = mutableListOf<ModelMessage>()
    private var truncationCount = 0
    private var sequence = 0

    override val truncatedCount: Int get() = truncationCount

    override fun start(systemPrompt: String, userPrompt: String) {
        messages.clear()
        truncationCount = 0
        messages += ModelMessage.system(systemPrompt)
        messages += ModelMessage.user(userPrompt)
    }

    override fun restore(saved: List<ModelMessage>) {
        messages.clear()
        truncationCount = 0
        messages += saved
    }

    override fun addAssistant(content: String, toolCalls: List<ModelToolCall>) {
        messages += ModelMessage.assistant(content, toolCalls)
    }

    override fun addToolResult(
        callId: String,
        toolName: String,
        content: String,
        path: String?,
        status: ToolContextStatus,
    ) {
        // One tool result per tool call: resuming a paused run, or a repeated
        // model turn, must not duplicate what is already in the conversation.
        if (callId.isNotBlank() && messages.any { it.role == ModelRole.TOOL && it.toolCallId == callId }) return

        val bounded = ContextTruncator.truncate(content, budget.maxToolResultChars)
        if (bounded.truncated) truncationCount++
        val now = clock()
        engine.addItems(
            sessionId,
            listOf(
                ContextItem(
                    id = "tool:" + callId.ifBlank { "step-${++sequence}" },
                    source = ContextSource.TOOL_RESULT,
                    content = bounded.text,
                    priority = ContextPriority.NORMAL,
                    relevance = ContextRelevance.toolResult(status),
                    path = path,
                    title = toolName,
                    metadata = ContextMetadata(
                        reason = "Result of $toolName (${status.name.lowercase()})",
                        selectedBecause = ContextReason.TOOL_RESULT,
                        toolId = toolName,
                        toolStatus = status,
                        timestampMillis = now,
                        originalChars = bounded.originalChars,
                    ),
                    truncated = bounded.truncated,
                    originalChars = bounded.originalChars,
                    createdAtMillis = now,
                ),
            ),
        )
        messages += ModelMessage.tool(callId, bounded.text, toolName)
    }

    override fun messages(): List<ModelMessage> {
        trim()
        return messages.toList()
    }

    override fun items(): List<ContextItem> = engine.items(sessionId)

    /**
     * Drops the oldest exchange groups until the conversation fits the
     * message-count and character budgets. At least the newest group survives.
     */
    private fun trim() {
        while (true) {
            val groups = groups()
            if (groups.size <= 1) return
            val overMessages = messages.size > budget.maxConversationMessages
            val overChars = messages.sumOf { it.content.length } > budget.maxConversationChars
            if (!overMessages && !overChars) return
            val oldest = groups.first()
            messages.subList(oldest.first, oldest.last + 1).clear()
            truncationCount++
        }
    }

    /**
     * Index ranges of the conversation after the preserved prefix (system
     * instruction + original task). An assistant message that requested tool
     * calls owns the tool messages that follow it.
     */
    private fun groups(): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var index = PRESERVED_PREFIX
        while (index < messages.size) {
            val message = messages[index]
            if (message.role == ModelRole.ASSISTANT && message.toolCalls.isNotEmpty()) {
                var end = index + 1
                while (end < messages.size && messages[end].role == ModelRole.TOOL) end++
                result += index until end
                index = end
            } else {
                result += index..index
                index++
            }
        }
        return result
    }

    private companion object {
        /** System instruction and the original user task are always kept. */
        const val PRESERVED_PREFIX = 2
    }
}
