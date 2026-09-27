package dev.forge.ide.agent.ui

import dev.forge.ide.agent.domain.AgentEvent
import dev.forge.ide.agent.domain.AgentEventSink
import dev.forge.ide.agent.domain.AgentRole
import dev.forge.ide.agent.domain.AgentRunRequest
import dev.forge.ide.agent.orchestrator.AgentOrchestrator
import dev.forge.ide.model.ModelConfig
import dev.forge.ide.ui.ide.data.AgentSession
import dev.forge.ide.ui.ide.data.AgentStreamEvent
import dev.forge.ide.ui.ide.model.AgentActivity
import dev.forge.ide.ui.ide.model.AgentActivityStatus

/**
 * Bridges the Agent Core orchestrator to the IDE [AgentSession] port.
 * UI stays free of model, tool, and orchestrator types.
 */
class OrchestratorAgentSession(
    private val orchestrator: AgentOrchestrator,
    private val modelConfig: () -> ModelConfig,
    private val workspaceId: String? = null,
) : AgentSession {

    override suspend fun run(input: String, onEvent: (AgentStreamEvent) -> Unit) {
        // No model online means no agent turn. Say so in user terms, and never fall
        // back to an endpoint the user did not select.
        if (modelConfig().validate().isNotEmpty()) {
            onEvent(AgentStreamEvent.Failed(NO_MODEL_ONLINE))
            return
        }

        val sink = AgentEventSink { event ->
            mapEvent(event)?.let(onEvent)
        }
        val result = orchestrator.run(
            request = AgentRunRequest(
                prompt = input,
                workspaceId = workspaceId,
            ),
            modelConfig = modelConfig(),
            sink = sink,
        )
        when (result.status) {
            dev.forge.ide.agent.domain.AgentStatus.COMPLETED ->
                onEvent(AgentStreamEvent.Completed(result.summary))
            dev.forge.ide.agent.domain.AgentStatus.CANCELLED ->
                onEvent(AgentStreamEvent.Failed("Cancelled"))
            else -> {
                val message = result.errors.firstOrNull()?.message ?: result.summary
                onEvent(AgentStreamEvent.Failed(message))
            }
        }
    }

    private companion object {
        const val NO_MODEL_ONLINE =
            "No model is online. Open Settings → Models, select a model and connect it first."
    }
}

internal fun mapEvent(event: AgentEvent): AgentStreamEvent? = when (event) {
    is AgentEvent.Thinking -> AgentStreamEvent.Activity(
        AgentActivity(AgentActivityStatus.THINKING, event.detail ?: "Thinking"),
    )

    is AgentEvent.ToolCallStarted -> AgentStreamEvent.Activity(
        AgentActivity(AgentActivityStatus.USING_TOOL, "Using tool · ${event.toolName}"),
    )

    is AgentEvent.SubAgentStarted -> AgentStreamEvent.AgentChanged(
        role = event.role.name,
        label = displayName(event.role),
    )

    is AgentEvent.SubAgentCompleted -> AgentStreamEvent.AgentChanged(
        role = AgentRole.MAIN.name,
        label = displayName(AgentRole.MAIN),
    )

    is AgentEvent.OutputDelta -> AgentStreamEvent.Chunk(event.text)

    else -> null
}

private fun displayName(role: AgentRole): String = when (role) {
    AgentRole.MAIN -> "Main"
    AgentRole.EXPLORER -> "Explorer"
    AgentRole.RESEARCHER -> "Researcher"
    AgentRole.CODER -> "Coder"
    AgentRole.DEBUGGER -> "Debugger"
    AgentRole.REVIEWER -> "Reviewer"
    AgentRole.TESTER -> "Tester"
}
