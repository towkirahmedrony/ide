package com.agentx.app.agent.main

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentEventSink
import com.agentx.app.agent.domain.AgentPlan
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.AgentStep
import com.agentx.app.agent.domain.AgentTask
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.runtime.AgentLoopRequest
import com.agentx.app.agent.runtime.ResumedPermission
import com.agentx.app.agent.runtime.SubAgentInvoker
import com.agentx.app.agent.runtime.nowMillis
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelMessage

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
