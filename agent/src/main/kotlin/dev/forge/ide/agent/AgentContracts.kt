package dev.forge.ide.agent

import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.architecture.LayerStatus

enum class AgentEventType {
    STATUS,
    MESSAGE,
    TOOL_CALL,
    COMPLETED,
}

/** Incremental output produced while an agent runs. */
data class AgentEvent(
    val type: AgentEventType,
    val payload: String,
)

/** Declarative description of an agent and the capabilities it may use. */
data class AgentDefinition(
    val id: String,
    val name: String,
    val instructions: String,
    val modelId: String? = null,
    val toolIds: List<String> = emptyList(),
    val skillIds: List<String> = emptyList(),
)

data class AgentRunInput(
    val input: String,
    val sessionId: String? = null,
)

/** A runnable agent. Implemented by the Agent Core in a later task. */
interface Agent {
    val definition: AgentDefinition

    suspend fun run(input: AgentRunInput, onEvent: (AgentEvent) -> Unit)
}

/** Creates and tracks agents from their definitions. */
interface AgentRuntime {
    fun register(agent: Agent)

    fun definitions(): List<AgentDefinition>

    fun create(definitionId: String): Agent?
}

/** Specification for a delegated subagent. */
data class SubagentSpec(
    val id: String,
    val name: String,
    val instructions: String,
    val modelId: String? = null,
    val toolIds: List<String> = emptyList(),
)

/** Port for running subagents on behalf of a parent agent. */
interface SubagentDelegator {
    suspend fun delegate(spec: SubagentSpec, input: AgentRunInput): String
}

val AGENT_LAYER = LayerDescriptor(
    id = "agent",
    title = "Agent Core",
    summary = "Runs agents and subagents over the model, tool, context, and workspace layers.",
    status = LayerStatus.CONTRACT_ONLY,
)
