package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.capability.ModelCapability

/**
 * Minimum capabilities each agent role needs. Kept in one place so later
 * quota-aware selection can reuse the same contract without scanning
 * AgentLoop or individual providers.
 */
object AgentRoleRequirements {

    val TOOL_ENABLED: Set<ModelCapability> = setOf(
        ModelCapability.TOOL_CALLING,
        ModelCapability.STREAMING,
    )

    fun required(role: AgentRole): Set<ModelCapability> = when (role) {
        AgentRole.MAIN,
        AgentRole.EXPLORER,
        AgentRole.RESEARCHER,
        AgentRole.CODER,
        AgentRole.DEBUGGER,
        AgentRole.REVIEWER,
        AgentRole.TESTER,
        AgentRole.PLANNER,
        AgentRole.FAST_CODER,
        AgentRole.SECURITY_REVIEWER,
        AgentRole.DOCS,
        AgentRole.COMMIT_PR,
        -> TOOL_ENABLED
    }

    fun requiresToolCalling(role: AgentRole): Boolean =
        ModelCapability.TOOL_CALLING in required(role)
}
