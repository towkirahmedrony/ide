package dev.forge.ide.agent

import dev.forge.ide.agent.domain.AgentRole

@Deprecated("Use Agent Core domain types in dev.forge.ide.agent.domain")
enum class AgentEventType {
    STATUS,
    MESSAGE,
    TOOL_CALL,
    COMPLETED,
}

/** Incremental output produced while an agent runs. */
@Deprecated("Use dev.forge.ide.agent.domain.AgentEvent")
data class AgentEvent(
    val type: AgentEventType,
    val payload: String,
)

/** Declarative description of an agent and the capabilities it may use. */
@Deprecated("Use dev.forge.ide.agent.domain.AgentDefinition")
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

/** A runnable agent. Implemented by the Agent Core. */
interface Agent {
    val definition: AgentDefinition

    suspend fun run(input: AgentRunInput, onEvent: (AgentEvent) -> Unit)
}

/** Creates and tracks agents from their definitions. */
interface AgentRuntimePort {
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
    val role: AgentRole? = null,
)

/** Port for running subagents on behalf of a parent agent. */
interface SubagentDelegator {
    suspend fun delegate(spec: SubagentSpec, input: AgentRunInput): String
}
