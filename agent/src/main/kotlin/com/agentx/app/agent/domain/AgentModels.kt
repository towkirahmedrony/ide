package com.agentx.app.agent.domain

import com.agentx.app.agent.prompt.PromptVariables
import com.agentx.app.context.AgentAttachment
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

    /**
     * Runtime ceiling for this definition.
     *
     * [isReadOnly] forbids workspace mutation; it does not strip a non-mutating
     * [PermissionLevel.NETWORK] ceiling. Collapsing NETWORK to READ_ONLY is what
     * previously removed the researcher's web tools at runtime.
     */
    val effectivePermission: PermissionLevel
        get() = when {
            !isReadOnly -> permissionLevel
            permissionLevel == PermissionLevel.NETWORK -> PermissionLevel.NETWORK
            else -> PermissionLevel.READ_ONLY
        }
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
    /**
     * Set when the run paused because one of its delegated specialists parked for
     * permission. The permission belongs to the child, so this carries the child's
     * identity and its own resume state rather than flattening the pause into a
     * failure.
     */
    val delegatedPermissionPause: DelegatedPermissionPause? = null,
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

/**
 * Parent-side view of a delegated specialist that parked for permission.
 *
 * The permission belongs to the child, so the parent keeps the child's session
 * identity, the pending decision, and the child's own resume context. The
 * parent's own conversation snapshot is carried on the enclosing
 * [AgentResult.resumeContext], and its assistant tool batch here, so the parent
 * can be resumed after the child finishes without re-running the delegation.
 */
data class DelegatedPermissionPause(
    val childSessionId: String,
    val childRole: AgentRole,
    val pendingPermission: PendingPermission,
    /** The specialist's conversation snapshot, needed to resume the child later. */
    val childResumeContext: List<ModelMessage> = emptyList(),
    /**
     * The parent's assistant tool batch at the moment of the pause, in order.
     * [parentPendingIndex] marks the delegate call, so resume can finish any
     * sibling that had not run yet without re-dispatching the delegation.
     */
    val parentBatch: List<PendingToolCall> = emptyList(),
    /** Position of the delegate call within [parentBatch]. */
    val parentPendingIndex: Int = 0,
) {
    /** The parent's delegate call whose result the child's completion satisfies. */
    val delegateToolCallId: String
        get() = parentBatch.getOrNull(parentPendingIndex)?.toolCallId.orEmpty()

    /**
     * [parentBatch] with the delegate call marked completed, so resuming the
     * parent dispatches only the siblings that never ran — never the delegation.
     */
    val parentBatchForResume: List<PendingToolCall>
        get() = parentBatch.map { call ->
            if (call.index == parentPendingIndex) call.copy(completed = true) else call
        }
}

/**
 * A parked tool call plus the user's decision, used to resume a run that
 * stopped in [AgentStatus.WAITING_FOR_PERMISSION].
 */
data class ResumedPermission(
    val toolName: String,
    val arguments: JsonObject,
    val reason: String,
    val toolCallId: String,
    val approved: Boolean,
    /**
     * The whole assistant message the decision belongs to, and the position of the
     * decided call in it.
     *
     * Carried through the pause so the resumed run can finish every sibling that had
     * not executed yet, in order, rather than sending the provider an assistant
     * message with fewer tool results than tool calls.
     */
    val batch: List<PendingToolCall> = emptyList(),
    val pendingIndex: Int = 0,
)

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
    /** Conversation snapshot to restore when resuming a paused specialist. */
    val resumeContext: List<ModelMessage> = emptyList(),
    /** The user's decision on a parked tool call; present only when resuming. */
    val resumePermission: ResumedPermission? = null,
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
    /** Set when the specialist parked in [AgentStatus.WAITING_FOR_PERMISSION]. */
    val pendingPermission: PendingPermission? = null,
    /** The specialist's conversation snapshot, needed to resume it later. */
    val resumeContext: List<ModelMessage> = emptyList(),
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
        pendingPermission = pendingPermission,
        resumeContext = resumeContext,
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
    /**
     * Files the user attached to this message.
     *
     * Transient, exactly like [selectedFile]: an attachment is part of *this* turn's
     * request, not part of the stored message. The Context Engine loads each one as a
     * workspace file, so nothing here is a second copy of the file or a second reader.
     */
    val attachments: List<AgentAttachment> = emptyList(),
    /**
     * The skills the user picked for this message, or null for the role's own set.
     *
     * Null preserves the existing behaviour exactly. A set narrows what the role would
     * contribute — it is intersected with the role's resolution inside
     * [com.agentx.app.context.SkillContextResolver], so it can never add a skill the
     * role was not entitled to, and validity, enablement and role assignment still
     * decide first.
     */
    val skillIds: Set<String>? = null,
    /** The selected/open file in the editor. */
    val selectedFile: String? = null,
    /** Prior conversation turns, oldest first. */
    val conversation: List<ModelMessage> = emptyList(),
    /** Limits applied to the context the Context Engine builds. */
    val contextBudget: ContextBudget = ContextBudget.DEFAULT,
)
