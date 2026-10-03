package com.agentx.app.agent.specialized

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentDefinition
import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentEventSink
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.SubAgentRequest
import com.agentx.app.agent.domain.SubAgentResult
import com.agentx.app.agent.domain.restrictTo
import com.agentx.app.agent.policy.AgentToolPolicy
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.runtime.AgentLoopRequest
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.model.ModelConfig
import com.agentx.app.tools.ToolDefinition

interface SpecializedAgent {
    val definition: AgentDefinition

    suspend fun run(
        request: SubAgentRequest,
        modelConfig: ModelConfig,
        sink: AgentEventSink,
        onCancelled: () -> Boolean,
    ): SubAgentResult
}

class SpecializedAgentRegistry(agents: List<SpecializedAgent>) {
    private val byRole = agents.associateBy { it.definition.role }

    fun get(role: AgentRole): SpecializedAgent? = byRole[role]

    fun all(): List<SpecializedAgent> = byRole.values.toList()
}

class DefaultSpecializedAgent(
    override val definition: AgentDefinition,
    private val loop: AgentLoop,
    private val bridge: AgentToolBridge,
    /** Every name the registry holds, resolved once per run instead of per call. */
    private val availableTools: () -> List<String>,
) : SpecializedAgent {

    override suspend fun run(
        request: SubAgentRequest,
        modelConfig: ModelConfig,
        sink: AgentEventSink,
        onCancelled: () -> Boolean,
    ): SubAgentResult {
        val permission = definition.effectivePermission.restrictTo(request.permissionLevel)

        // The role's policy is the ceiling, and it is applied here rather than
        // trusted from the caller. Previously a sub-agent that named no tools was
        // handed the entire registry; now it receives exactly the tools its role
        // was granted, and a request may only ever narrow that further.
        val definitions = bridge.definitionsFor(availableTools()).associateBy { it.name }
        val definitionOf: (String) -> ToolDefinition? = { name -> definitions[name] }
        val policyTools = AgentToolPolicy.effectiveToolIds(
            role = definition.role,
            definitionOf = definitionOf,
            requested = request.allowedTools.takeIf { it.isNotEmpty() },
        )

        // The permission level of this run is a second, capability-level ceiling on
        // top of the role's policy: a read-only run cannot hold a mutating tool even
        // if the role is normally allowed to write.
        val allowed = bridge.filterAllowed(policyTools, permission)
        val maxSteps = (request.maxSteps ?: definition.maxSteps).coerceAtLeast(1)

        val result = loop.run(
            request = AgentLoopRequest(
                sessionId = request.sessionId,
                parentSessionId = request.parentSessionId,
                definition = definition,
                allowedTools = allowed,
                permissionLevel = permission,
                maxSteps = maxSteps,
                userPrompt = request.task,
                objective = request.objective,
                scopedContext = request.scopedContext,
                workspaceId = request.workspaceId,
                modelConfig = modelConfig,
                contextBudget = request.contextBudget,
                promptVariables = request.promptVariables,
                delegationState = request.delegationState,
            ),
            sink = sink,
            subAgentInvoker = null,
            onCancelled = onCancelled,
        )
        return SubAgentResult(
            sessionId = result.sessionId,
            role = definition.role,
            status = result.status,
            summary = result.summary,
            findings = result.findings,
            filesInspected = result.filesInspected,
            filesChanged = result.filesChanged,
            toolActions = result.toolActions,
            errors = result.errors,
        )
    }
}

class SpecializedAgentFactory(
    private val loop: AgentLoop,
    private val bridge: AgentToolBridge,
    /**
     * Kept so the existing wiring keeps compiling. The value is the registry's own
     * names, which [DefaultSpecializedAgent] uses to resolve definitions — it is no
     * longer a fallback tool list, because a sub-agent's tools now come from its
     * role policy.
     */
    private val availableTools: () -> List<String>,
) {
    fun create(role: AgentRole): SpecializedAgent {
        require(role != AgentRole.MAIN) { "Main is not a specialized agent" }
        return DefaultSpecializedAgent(
            definition = AgentCatalog.definition(role),
            loop = loop,
            bridge = bridge,
            availableTools = availableTools,
        )
    }

    fun createAll(): SpecializedAgentRegistry = SpecializedAgentRegistry(
        AgentRole.entries.filter { it != AgentRole.MAIN }.map(::create),
    )
}

fun unknownSubAgent(request: SubAgentRequest): SubAgentResult = SubAgentResult(
    sessionId = request.sessionId,
    role = request.role,
    status = AgentStatus.FAILED,
    summary = "No specialized agent registered for ${request.role}",
    errors = listOf(
        AgentError(
            code = AgentErrorCode.UNKNOWN_AGENT,
            message = "No specialized agent registered for ${request.role}",
            role = request.role,
            sessionId = request.sessionId,
        ),
    ),
)
