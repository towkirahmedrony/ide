package com.agentx.app.agent.domain

import com.agentx.app.agent.prompt.PromptVariables
import com.agentx.app.context.ContextBudget
import com.agentx.app.model.ModelMessage
import com.agentx.app.model.json.JsonObject

data class AgentDefinition(
    val role: AgentRole,
    val name: String,
    val systemInstructions: String,
    val allowedTools: List<String>,
    val permissionLevel: PermissionLevel,
    val isReadOnly: Boolean,
    val maxSteps: Int,
    val modelPreference: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Agent name must not be blank" }
        require(maxSteps > 0) { "maxSteps must be positive" }
    }

    val effectivePermission: PermissionLevel
        get() = if (isReadOnly) PermissionLevel.READ_ONLY else permissionLevel
}

data class AgentTask(
    val id: String,
    val prompt: String,
    val objective: String? = null,
    val workspaceId: String? = null,
)

data class AgentStep(
    val index: Int,
    val title: String,
    val role: AgentRole = AgentRole.MAIN,
    val status: AgentStatus = AgentStatus.IDLE,
    val detail: String? = null,
)

data class AgentPlan(
    val steps: List<AgentStep> = emptyList(),
    val revision: Int = 1,
)

data class ToolActionRecord(
    val toolName: String,
    val success: Boolean,
    val summary: String,
    val path: String? = null,
)

data class AgentSession(
    val id: String,
    val parentSessionId: String? = null,
    val role: AgentRole,
    val status: AgentStatus,
    val task: AgentTask,
    val plan: AgentPlan? = null,
    val steps: List<AgentStep> = emptyList(),
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val workspaceId: String? = null,
    /** User-visible title; derived from the first user message when blank. */
    val title: String? = null,
    /** Model provider last used by this session, when known. */
    val modelProviderId: String? = null,
    /** Model identifier last used by this session, when known. */
    val modelId: String? = null,
)

data class AgentResult(
    val sessionId: String,
    val status: AgentStatus,
    val summary: String,
    val findings: List<String> = emptyList(),
    val filesInspected: List<String> = emptyList(),
    val filesChanged: List<String> = emptyList(),
    val toolActions: List<ToolActionRecord> = emptyList(),
    val errors: List<AgentError> = emptyList(),
    val plan: AgentPlan? = null,
    val role: AgentRole = AgentRole.MAIN,
    /** Cumulative execution accounting; populated by the execution engine. */
    val stepStats: AgentStepStats = AgentStepStats(),
    /** Set when the run paused with [AgentStatus.WAITING_FOR_PERMISSION]. */
    val pendingPermission: PendingPermission? = null,
    /** Conversation snapshot to restore when resuming a paused run. */
    val resumeContext: List<ModelMessage> = emptyList(),
)

/**
 * Execution accounting for one agent run. Every model call, tool call, and
 * sub-agent delegation counts against the run's step budget.
 */
data class AgentStepStats(
    val currentStep: Int = 0,
    val maxSteps: Int = 0,
    val modelCalls: Int = 0,
    val toolCalls: Int = 0,
    val subAgentCalls: Int = 0,
    val elapsedMillis: Long = 0,
) {
    override fun toString(): String =
        "Step $currentStep / $maxSteps · model: $modelCalls · tools: $toolCalls · sub-agents: $subAgentCalls"
}

/**
 * One tool call belonging to the assistant message that is waiting for a decision.
 *
 * A single model response may request several tools at once, so the parked state has
 * to remember the whole message: resuming from the blocked call alone would discard
 * its siblings and leave an assistant message whose tool calls have no matching
 * results, which is not a valid transcript to send back to the provider.
 */
data class PendingToolCall(
    val toolCallId: String,
    val toolName: String,
    /** Structured arguments, preserved so resume replays the exact same call. */
    val arguments: JsonObject = emptyMap(),
    /** Position in the assistant message; resume dispatches in this order. */
    val index: Int = 0,
    /** True when this call already produced a result before the pause. */
    val completed: Boolean = false,
)

/** A tool call parked while waiting for the user's approval decision. */
data class PendingPermission(
    val toolName: String,
    /** Structured tool arguments, preserved for the eventual execution. */
    val arguments: JsonObject = emptyMap(),
    val reason: String,
    val toolCallId: String,
    /** Permission levels the tool needs, shown in the approval prompt. */
    val requiredPermissions: Set<String> = emptySet(),
    /**
     * Every tool call of the assistant message this decision belongs to, in order.
     *
     * The assistant message is never reconstructed from the blocked call alone: the
     * siblings are carried here so an approval finishes the message instead of losing
     * the calls that had not run yet.
     */
    val batch: List<PendingToolCall> = emptyList(),
    /** Index of the call awaiting a decision; earlier calls have already run. */
    val pendingIndex: Int = 0,
) {
    /**
     * The calls still to run once a decision is made — the decided call and any
     * sibling after it — in their original order.
     */
    val remaining: List<PendingToolCall>
        get() = batch.filter { !it.completed && it.index >= pendingIndex }.sortedBy { it.index }

    /**
     * The complete assistant tool-call message as it must be replayed on resume.
     * Falls back to the single parked call for state written before batches existed,
     * so an older paused run still resumes rather than misreporting itself.
     */
    val batchOrSelf: List<PendingToolCall>
        get() = batch.ifEmpty {
            listOf(PendingToolCall(toolCallId = toolCallId, toolName = toolName, arguments = arguments))
        }
}

data class SubAgentRequest(
    val role: AgentRole,
    val task: String,
    val objective: String,
    val scopedContext: String = "",
    val allowedTools: List<String> = emptyList(),
    val permissionLevel: PermissionLevel? = null,
    val maxSteps: Int? = null,
    val parentSessionId: String,
    val workspaceId: String? = null,
    val sessionId: String,
    /** Template variables used to resolve this sub-agent's system prompt. */
    val promptVariables: PromptVariables = PromptVariables.EMPTY,
    /**
     * Delegation accounting inherited from the parent, with depth already
     * incremented. The specialist carries it so a nested delegation (should the
     * role ever gain the tool) still sees the whole-run limits.
     */
    val delegationState: com.agentx.app.agent.delegation.DelegationState = com.agentx.app.agent.delegation.DelegationState(),
    /**
     * The context budget this specialist should run with, derived from the base
     * budget by [com.agentx.app.agent.delegation.SpecialistContextBudgets]. The
     * default keeps the previous behaviour for callers that do not scope it.
     */
    val contextBudget: ContextBudget = ContextBudget.DEFAULT,
)

data class SubAgentResult(
    val sessionId: String,
    val role: AgentRole,
    val status: AgentStatus,
    val summary: String,
    val findings: List<String> = emptyList(),
    val filesInspected: List<String> = emptyList(),
    val filesChanged: List<String> = emptyList(),
    val toolActions: List<ToolActionRecord> = emptyList(),
    val errors: List<AgentError> = emptyList(),
) {
    fun toAgentResult(plan: AgentPlan? = null): AgentResult = AgentResult(
        sessionId = sessionId,
        status = status,
        summary = summary,
        findings = findings,
        filesInspected = filesInspected,
        filesChanged = filesChanged,
        toolActions = toolActions,
        errors = errors,
        plan = plan,
        role = role,
    )
}

data class AgentRunRequest(
    val prompt: String,
    val sessionId: String? = null,
    val workspaceId: String? = null,
    val timeoutMillis: Long? = null,
    /** Context the caller already assembled and wants included verbatim. */
    val context: String = "",
    /** Files the user explicitly pointed at, most relevant first. */
    val mentionedFiles: List<String> = emptyList(),
    /** The selected/open file in the editor. */
    val selectedFile: String? = null,
    /** Prior conversation turns, oldest first. */
    val conversation: List<ModelMessage> = emptyList(),
    /** Limits applied to the context the Context Engine builds. */
    val contextBudget: ContextBudget = ContextBudget.DEFAULT,
)
