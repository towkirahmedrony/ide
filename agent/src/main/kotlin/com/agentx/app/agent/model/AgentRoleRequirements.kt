package com.agentx.app.agent.model

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.model.capability.ModelCapability

/**
 * Minimum capabilities each agent role needs. Kept in one place so later
 * quota-aware selection can reuse the same contract without scanning
 * AgentLoop or individual providers.
 *
 * The requirement is derived from what the role actually does at execution time,
 * which is [com.agentx.app.agent.policy.AgentToolPolicy]'s grant for the role —
 * the one place a role's tools are declared:
 *
 *  - a role that must *act* on the workspace or repository (write a file, run a
 *    command, commit or push) cannot do its job without invoking tools, so it
 *    requires [ModelCapability.TOOL_CALLING];
 *  - a role that only inspects, reviews, plans or researches produces its answer
 *    from ordinary model responses over the context the runtime supplies, so it
 *    requires only [ModelCapability.TEXT_GENERATION].
 *
 * Requiring tool calling for an analysis role is what made a remote API model
 * look unusable even though ordinary text generation and routing worked. Text
 * generation is the baseline every runnable model has, so a text-only role is
 * never blocked by an unverified tool capability, while a tool-requiring role
 * still refuses a model whose tool capability is unknown.
 *
 * MAIN/CODER/DEBUGGER are action roles, so they keep the tool contract the local
 * Devstral 24B configuration was validated with.
 */
object AgentRoleRequirements {

    /**
     * The capabilities a role that must invoke tools needs: tool calling, plus
     * streamed completions.
     */
    val TOOL_ENABLED: Set<ModelCapability> = setOf(
        ModelCapability.TOOL_CALLING,
        ModelCapability.STREAMING,
    )

    /**
     * The capabilities a role that only generates text needs. Text generation is
     * the baseline of being a model, so this set is never blocked by an unverified
     * tool, streaming or reasoning capability.
     */
    val TEXT_ONLY: Set<ModelCapability> = setOf(ModelCapability.TEXT_GENERATION)

    fun required(role: AgentRole): Set<ModelCapability> = when (role) {
        // Action roles: their granted tools mutate the workspace, run commands or
        // write to the repository, so they cannot do their job without tool calling.
        AgentRole.MAIN,
        AgentRole.CODER,
        AgentRole.DEBUGGER,
        AgentRole.TESTER,
        AgentRole.FAST_CODER,
        AgentRole.DOCS,
        AgentRole.COMMIT_PR,
        -> TOOL_ENABLED

        // Analysis roles: EXPLORER/PLANNER map and plan from the context they are
        // given, REVIEWER/SECURITY_REVIEWER review a change set, RESEARCHER
        // summarizes sources. They answer through an ordinary model response, so
        // their eligibility must not depend on an unverified tool capability.
        AgentRole.EXPLORER,
        AgentRole.RESEARCHER,
        AgentRole.REVIEWER,
        AgentRole.PLANNER,
        AgentRole.SECURITY_REVIEWER,
        -> TEXT_ONLY
    }

    fun requiresToolCalling(role: AgentRole): Boolean =
        ModelCapability.TOOL_CALLING in required(role)
}
