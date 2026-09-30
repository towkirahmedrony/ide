package com.agentx.app.agent.conversation

import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentStatus

/**
 * Deterministic session memory. Built from structured turn results, never from
 * an extra model call.
 */
object SessionSummaryBuilder {

    const val MAX_LIST_ITEMS: Int = 12
    const val MAX_ITEM_CHARS: Int = 240

    fun update(
        previous: SessionSummary,
        userPrompt: String,
        result: AgentResult,
        now: Long,
    ): SessionSummary {
        val completed = previous.completed.toMutableList()
        val files = previous.discoveredFiles.toMutableList()
        val unresolved = previous.unresolved.toMutableList()
        val highlights = previous.toolHighlights.toMutableList()
        val decisions = previous.decisions.toMutableList()

        result.filesInspected.forEach { remember(files, it) }
        result.filesChanged.forEach { remember(files, it) }

        result.toolActions.forEach { action ->
            val line = buildString {
                append(action.toolName)
                if (!action.success) append(" (failed)")
                append(": ")
                append(action.summary)
                action.path?.takeIf { it.isNotBlank() }?.let { append(" [").append(it).append(']') }
            }
            remember(highlights, line)
            action.path?.let { remember(files, it) }
        }

        if (result.status == AgentStatus.COMPLETED && result.summary.isNotBlank()) {
            remember(completed, result.summary)
            unresolved.removeAll { it.equals(userPrompt.trim(), ignoreCase = true) }
        }
        if (result.status == AgentStatus.FAILED || result.status == AgentStatus.MAX_STEPS_REACHED) {
            val detail = result.errors.firstOrNull()?.message ?: result.summary
            if (detail.isNotBlank()) remember(unresolved, detail)
        }
        if (result.findings.isNotEmpty()) {
            result.findings.forEach { remember(decisions, it) }
        }

        return previous.copy(
            currentTask = userPrompt.trim().takeIf { it.isNotEmpty() } ?: previous.currentTask,
            completed = trim(completed),
            discoveredFiles = trim(files),
            decisions = trim(decisions),
            unresolved = trim(unresolved),
            toolHighlights = trim(highlights),
            updatedAtMillis = now,
        )
    }

    fun fromConversation(conversation: AgentConversation, now: Long): SessionSummary {
        var summary = conversation.summary
        val userTurns = conversation.messages.filter { it.role == MessageRole.USER }
        userTurns.lastOrNull()?.content?.text?.takeIf { it.isNotBlank() }?.let { prompt ->
            summary = summary.copy(currentTask = prompt, updatedAtMillis = now)
        }
        conversation.messages.forEach { message ->
            when (message.role) {
                MessageRole.TOOL -> {
                    val name = message.content.toolName ?: "tool"
                    val body = message.content.toolResult ?: message.content.text
                    if (body.isNotBlank()) {
                        summary = summary.copy(
                            toolHighlights = trim(summary.toolHighlights + "$name: $body"),
                            discoveredFiles = message.content.toolName?.let { summary.discoveredFiles }
                                ?: summary.discoveredFiles,
                            updatedAtMillis = now,
                        )
                    }
                }
                MessageRole.ERROR -> {
                    val text = message.content.text
                    if (text.isNotBlank()) {
                        summary = summary.copy(
                            unresolved = trim(summary.unresolved + text),
                            updatedAtMillis = now,
                        )
                    }
                }
                MessageRole.SUB_AGENT -> {
                    val text = message.content.text
                    if (text.isNotBlank()) {
                        summary = summary.copy(
                            completed = trim(summary.completed + text),
                            updatedAtMillis = now,
                        )
                    }
                }
                else -> Unit
            }
        }
        return summary
    }

    fun taskState(
        previous: SessionTaskState,
        userPrompt: String,
        result: AgentResult,
        workspaceId: String?,
    ): SessionTaskState {
        val plan = result.plan
        val completed = plan?.steps
            ?.filter { it.status == AgentStatus.COMPLETED }
            ?.map { it.title }
            .orEmpty()
            .ifEmpty { previous.completedSteps }
        val pending = plan?.steps
            ?.filter { it.status != AgentStatus.COMPLETED && it.status != AgentStatus.CANCELLED }
            ?.map { it.title }
            .orEmpty()
        val current = plan?.steps
            ?.firstOrNull { it.status == AgentStatus.RUNNING }
            ?.title
            ?: pending.firstOrNull()
        val files = (result.filesInspected + result.filesChanged + previous.relevantFiles)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_LIST_ITEMS)
        val lastTool = result.toolActions.lastOrNull()?.let { action ->
            "${action.toolName}: ${action.summary}"
        } ?: previous.lastToolResult
        val lastError = result.errors.lastOrNull()?.message ?: previous.lastError
        return SessionTaskState(
            activeTask = userPrompt.trim().takeIf { it.isNotEmpty() } ?: previous.activeTask,
            currentPlan = plan?.steps?.joinToString(" → ") { it.title } ?: previous.currentPlan,
            currentStep = current ?: previous.currentStep,
            completedSteps = trim(completed),
            pendingSteps = trim(pending),
            relevantFiles = files,
            lastToolResult = lastTool?.take(MAX_ITEM_CHARS),
            lastError = lastError?.take(MAX_ITEM_CHARS),
            workspaceId = workspaceId ?: previous.workspaceId,
        )
    }

    private fun remember(target: MutableList<String>, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        val existing = target.indexOfFirst { it.equals(trimmed, ignoreCase = true) }
        if (existing >= 0) target.removeAt(existing)
        target += trimmed.take(MAX_ITEM_CHARS)
    }

    private fun trim(items: List<String>): List<String> =
        items.map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeLast(MAX_LIST_ITEMS)
}
