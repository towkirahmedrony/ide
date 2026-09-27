package dev.forge.ide.agent.specialized

import dev.forge.ide.agent.catalog.AgentCatalog
import dev.forge.ide.agent.domain.AgentDefinition
import dev.forge.ide.agent.domain.AgentError
import dev.forge.ide.agent.domain.AgentErrorCode
import dev.forge.ide.agent.domain.AgentEventSink
import dev.forge.ide.agent.domain.AgentRole
import dev.forge.ide.agent.domain.AgentStatus
import dev.forge.ide.agent.domain.SubAgentRequest
import dev.forge.ide.agent.domain.SubAgentResult
import dev.forge.ide.agent.domain.restrictTo
import dev.forge.ide.agent.protocol.AgentProtocol
import dev.forge.ide.agent.runtime.AgentLoop
import dev.forge.ide.agent.runtime.AgentLoopRequest
import dev.forge.ide.agent.tools.AgentToolBridge
import dev.forge.ide.model.ModelConfig

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
    private val availableTools: () -> List<String>,
) : SpecializedAgent {

    override suspend fun run(
        request: SubAgentRequest,
        modelConfig: ModelConfig,
        sink: AgentEventSink,
        onCancelled: () -> Boolean,
    ): SubAgentResult {
        val permission = definition.effectivePermission.restrictTo(request.permissionLevel)
        val requestedTools = buildList {
            addAll(if (request.allowedTools.isEmpty()) availableTools() else request.allowedTools)
            addAll(definition.allowedTools)
            add(AgentProtocol.FINISH_TOOL)
        }
        val allowed = bridge.filterAllowed(requestedTools, permission)
        val maxSteps = (request.maxSteps ?: definition.maxSteps).coerceAtLeast(1)
        val preferredConfig = request.modelPreferenceConfig(modelConfig, definition)

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
                modelConfig = preferredConfig,
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

private fun SubAgentRequest.modelPreferenceConfig(
    base: ModelConfig,
    definition: AgentDefinition,
): ModelConfig {
    val preference = definition.modelPreference
    return if (preference.isNullOrBlank()) base else base.copy(model = preference)
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
