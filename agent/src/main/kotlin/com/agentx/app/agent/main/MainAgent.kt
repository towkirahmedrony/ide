package com.agentx.app.agent.main

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentEventSink
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentTask
import com.agentx.app.agent.prompt.PromptVariables
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.runtime.AgentLoopRequest
import com.agentx.app.agent.runtime.ResumedPermission
import com.agentx.app.agent.runtime.SubAgentInvoker
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.context.ContextBudget
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelMessage

data class MainAgentRequest(
    val sessionId: String,
    val task: AgentTask,
    val context: String = "",
    val modelConfig: ModelConfig,
    val maxSteps: Int? = null,
    val contextBudget: ContextBudget = ContextBudget.DEFAULT,
    val resumeContext: List<ModelMessage> = emptyList(),
    val resumePermission: ResumedPermission? = null,
    val promptVariables: PromptVariables = PromptVariables.EMPTY,
    val requiresWorkspace: Boolean = true,
)

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
        // হার্ডকোডেড ৩টা প্ল্যান এখান থেকে রিমুভ করা হয়েছে

        val allowed = bridge.filterAllowed(definition.allowedTools, definition.effectivePermission)
        return loop.run(
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
                contextBudget = request.contextBudget,
                resumeContext = request.resumeContext,
                resumePermission = request.resumePermission,
                promptVariables = request.promptVariables,
                requiresWorkspace = request.requiresWorkspace,
            ),
            sink = sink,
            subAgentInvoker = subAgentInvoker,
            onCancelled = onCancelled,
        )
    }
}
