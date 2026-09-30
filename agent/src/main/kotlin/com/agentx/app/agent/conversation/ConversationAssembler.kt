package com.agentx.app.agent.conversation

import com.agentx.app.context.ContextBudget
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.ModelRole
import com.agentx.app.model.ModelToolCall
import com.agentx.app.model.json.Json
import com.agentx.app.model.json.JsonCodec
import com.agentx.app.model.json.JsonObject
import com.agentx.app.model.json.objectOrNull

/**
 * Reconstructs the model-ready conversation for one session without sending
 * unlimited history. Persistent storage stays complete; only a budgeted view
 * is returned.
 *
 * Strategy:
 * 1. recent messages
 * 2. important earlier messages (errors, failed tools, first user turn)
 * 3. relevant tool results
 * 4. session summary as a system note when older turns were dropped
 */
object ConversationAssembler {

    fun forModel(
        conversation: AgentConversation,
        budget: ContextBudget = ContextBudget.DEFAULT,
    ): List<ModelMessage> {
        val selected = select(conversation, budget)
        return toModelMessages(selected)
    }

    fun select(
        conversation: AgentConversation,
        budget: ContextBudget = ContextBudget.DEFAULT,
    ): List<ConversationMessage> {
        val messages = conversation.messages.filter { it.metadata.status != MessageStatus.STARTED }
        if (messages.isEmpty()) return emptyList()
        val limit = budget.maxConversationMessages.coerceAtLeast(0)
        val charLimit = budget.maxConversationChars.coerceAtLeast(1)
        if (limit == 0) return emptyList()

        val recent = messages.takeLast(limit)
        val recentIds = recent.map { it.id }.toHashSet()
        val important = messages.filter { it.id !in recentIds && isImportant(it) }
        val merged = (important + recent).distinctBy { it.id }.sortedBy { it.metadata.timestampMillis }

        val kept = mutableListOf<ConversationMessage>()
        var used = 0
        for (message in merged.asReversed()) {
            val size = messageSize(message)
            if (kept.size >= limit) break
            if (used + size > charLimit && kept.isNotEmpty()) break
            kept.add(0, message)
            used += size
        }
        return kept
    }

    fun toModelMessages(messages: List<ConversationMessage>): List<ModelMessage> =
        messages.mapNotNull(::toModelMessage)

    fun toModelMessage(message: ConversationMessage): ModelMessage? {
        val text = displayText(message)
        return when (message.role) {
            MessageRole.USER -> ModelMessage.user(text)
            MessageRole.ASSISTANT -> {
                val toolCalls = toolCallsOf(message)
                ModelMessage.assistant(text, toolCalls)
            }
            MessageRole.SYSTEM -> ModelMessage.system(text)
            MessageRole.TOOL -> {
                val callId = message.content.toolCallId.orEmpty()
                if (callId.isBlank()) {
                    ModelMessage.assistant("[tool ${message.content.toolName ?: "unknown"}] $text")
                } else {
                    ModelMessage.tool(callId, text, message.content.toolName)
                }
            }
            MessageRole.SUB_AGENT -> {
                val role = message.content.subAgentRole ?: "sub-agent"
                ModelMessage.assistant("[$role] $text")
            }
            MessageRole.ERROR -> ModelMessage.assistant("[error] $text")
        }
    }

    fun summaryMessage(summary: SessionSummary): ModelMessage? {
        val rendered = summary.render()
        if (rendered.isBlank()) return null
        return ModelMessage.system("Session memory:\n$rendered")
    }

    fun modelConversation(
        conversation: AgentConversation,
        budget: ContextBudget = ContextBudget.DEFAULT,
    ): List<ModelMessage> {
        val selected = select(conversation, budget)
        val dropped = conversation.messages.size > selected.size
        val messages = mutableListOf<ModelMessage>()
        if (dropped) {
            summaryMessage(conversation.summary)?.let { messages += it }
            taskStateMessage(conversation.taskState)?.let { messages += it }
        }
        messages += toModelMessages(selected)
        return messages
    }

    fun taskStateMessage(state: SessionTaskState): ModelMessage? {
        if (state.isEmpty()) return null
        val body = buildString {
            state.activeTask?.let { append("activeTask=").append(it).append('\n') }
            state.currentPlan?.let { append("plan=").append(it).append('\n') }
            state.currentStep?.let { append("currentStep=").append(it).append('\n') }
            if (state.completedSteps.isNotEmpty()) {
                append("completedSteps=").append(state.completedSteps.joinToString(" | ")).append('\n')
            }
            if (state.pendingSteps.isNotEmpty()) {
                append("pendingSteps=").append(state.pendingSteps.joinToString(" | ")).append('\n')
            }
            if (state.relevantFiles.isNotEmpty()) {
                append("relevantFiles=").append(state.relevantFiles.joinToString(", ")).append('\n')
            }
            state.lastToolResult?.let { append("lastToolResult=").append(it).append('\n') }
            state.lastError?.let { append("lastError=").append(it).append('\n') }
            state.workspaceId?.let { append("workspace=").append(it).append('\n') }
        }.trim()
        if (body.isBlank()) return null
        return ModelMessage.system("Current task state:\n$body")
    }

    private fun isImportant(message: ConversationMessage): Boolean = when (message.role) {
        MessageRole.ERROR -> true
        MessageRole.TOOL -> message.metadata.toolSuccess == false
        MessageRole.USER -> true
        MessageRole.SUB_AGENT -> true
        else -> false
    }

    private fun messageSize(message: ConversationMessage): Int =
        displayText(message).length + (message.content.toolArguments?.length ?: 0)

    private fun displayText(message: ConversationMessage): String {
        val content = message.content
        return when (message.role) {
            MessageRole.TOOL -> {
                val result = content.toolResult ?: content.text
                val name = content.toolName
                if (name.isNullOrBlank()) result else "$name: $result"
            }
            MessageRole.SUB_AGENT -> content.text.ifBlank {
                listOfNotNull(content.subAgentRole, content.toolResult).joinToString(": ")
            }
            else -> content.text
        }
    }

    private fun toolCallsOf(message: ConversationMessage): List<ModelToolCall> {
        val name = message.content.toolName ?: return emptyList()
        if (message.role != MessageRole.ASSISTANT) return emptyList()
        val callId = message.content.toolCallId ?: return emptyList()
        val arguments = parseArguments(message.content.toolArguments)
        return listOf(ModelToolCall(id = callId, name = name, arguments = arguments))
    }

    private fun parseArguments(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            JsonCodec.parse(raw).objectOrNull() ?: emptyMap()
        }.getOrElse {
            mapOf("preview" to Json.of(raw.take(200)))
        }
    }
}
