package dev.forge.ide.agent.main

import dev.forge.ide.agent.catalog.AgentCatalog
import dev.forge.ide.agent.domain.AgentDefinition
import dev.forge.ide.agent.domain.AgentEvent
import dev.forge.ide.agent.domain.AgentEventSink
import dev.forge.ide.agent.domain.AgentPlan
import dev.forge.ide.agent.domain.AgentResult
import dev.forge.ide.agent.domain.AgentRole
import dev.forge.ide.agent.domain.AgentStatus
import dev.forge.ide.agent.domain.AgentStep
import dev.forge.ide.agent.domain.AgentTask
import dev.forge.ide.agent.runtime.AgentLoop
import dev.forge.ide.agent.runtime.AgentLoopRequest
import dev.forge.ide.agent.runtime.ResumedPermission
import dev.forge.ide.agent.runtime.SubAgentInvoker
import dev.forge.ide.agent.runtime.nowMillis
import dev.forge.ide.agent.tools.AgentToolBridge
import dev.forge.ide.model.ModelConfig
import dev.forge.ide.model.ModelMessage

data class MainAgentRequest(
    val sessionId: String,
    val task: AgentTask,
    val context: String = "",
    val modelConfig: ModelConfig,
    val maxSteps: Int? = null,
    /** Conversation snapshot restored when resuming a permission pause. */
    val resumeContext: List<ModelMessage> = emptyList(),
    /** The parked tool call plus the user's decision, when resuming. */
    val resumePermission: ResumedPermission? = null,
)

/**
 * Primary agent. It plans, delegates sequentially, and compiles the user-facing
 * result. Specialized domain work lives in sub-agents, not here.
 */
class MainAgent(
    private val loop: AgentLoop,
    private val bridge: AgentToolBridge,
) {
    val definition: AgentDefinition = AgentCatalog.MAIN

    suspend fun run(
        request: MainAgentRequest,
        sink: AgentEventSink,
        subAgentInvoker: SubAgentInvoker,
        onCancelled: () -> Boolean = { false },
    ): AgentResult {
        val plan = AgentPlan(
            steps = listOf(
                AgentStep(1, "Understand the task", AgentRole.MAIN, AgentStatus.RUNNING),
                AgentStep(2, "Delegate or act", AgentRole.MAIN, AgentStatus.IDLE),
                AgentStep(3, "Compile the result", AgentRole.MAIN, AgentStatus.IDLE),
            ),
        )
        sink.emit(AgentEvent.PlanUpdated(request.sessionId, plan, nowMillis()))

        val allowed = bridge.filterAllowed(definition.allowedTools, definition.effectivePermission)
        val result = loop.run(
            request = AgentLoopRequest(
                sessionId = request.sessionId,
                parentSessionId = null,
                definition = definition,
                allowedTools = allowed,
                permissionLevel = definition.effectivePermission,
                maxSteps = request.maxSteps ?: definition.maxSteps,
                userPrompt = request.task.prompt,
                objective = request.task.objective,
                scopedContext = request.context,
                workspaceId = request.task.workspaceId,
                modelConfig = request.modelConfig,
                resumeContext = request.resumeContext,
                resumePermission = request.resumePermission,
            ),
            sink = sink,
            subAgentInvoker = subAgentInvoker,
            onCancelled = onCancelled,
        )
        return result.copy(plan = plan.copy(revision = plan.revision + 1))
    }
}
