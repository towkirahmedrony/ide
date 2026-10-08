package com.agentx.app.agent.domain

import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolPermissionLevel as ToolGrant

enum class AgentRole {
    MAIN,
    EXPLORER,
    RESEARCHER,
    CODER,
    DEBUGGER,
    REVIEWER,
    TESTER,
    PLANNER,
    FAST_CODER,
    SECURITY_REVIEWER,
    DOCS,
    COMMIT_PR,
}

enum class AgentStatus {
    IDLE,
    PLANNING,
    RUNNING,
    WAITING_FOR_SUBAGENT,

    /** A tool call is parked until the user approves or denies it. */
    WAITING_FOR_PERMISSION,

    /** Reserved for a future user-input tool; not produced yet. */
    WAITING_FOR_INPUT,
    COMPLETED,
    CANCELLED,
    FAILED,

    /** The step budget was exhausted; distinct from a generic failure. */
    MAX_STEPS_REACHED,
    ;

    /** Terminal states; the execution loop must always end in one of these. */
    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, CANCELLED, FAILED, MAX_STEPS_REACHED)
}

/**
 * Maximum capability scope an agent may exercise. Combined with an allow-list
 * of tool names; never grants unrestricted filesystem or shell access.
 */
enum class PermissionLevel {
    READ_ONLY,
    WORKSPACE_WRITE,
    COMMAND_EXECUTION,
    NETWORK,
    GIT_WRITE,
    ;

    fun allowedCapabilities(): Set<ToolCapability> = when (this) {
        READ_ONLY -> setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM)
        WORKSPACE_WRITE -> setOf(
            ToolCapability.READ_ONLY,
            ToolCapability.MUTATING,
            ToolCapability.FILESYSTEM,
        )
        COMMAND_EXECUTION -> setOf(
            ToolCapability.READ_ONLY,
            ToolCapability.MUTATING,
            ToolCapability.FILESYSTEM,
            ToolCapability.SHELL,
        )
        NETWORK -> setOf(
            ToolCapability.READ_ONLY,
            ToolCapability.NETWORK,
            ToolCapability.FILESYSTEM,
        )
        GIT_WRITE -> setOf(
            ToolCapability.READ_ONLY,
            ToolCapability.GIT,
            ToolCapability.MUTATING,
            ToolCapability.NETWORK,
            ToolCapability.FILESYSTEM,
        )
    }

    fun allows(capabilities: Set<ToolCapability>): Boolean {
        if (ToolCapability.CREDENTIALS in capabilities) return false
        if (capabilities.isEmpty()) return true
        val allowed = allowedCapabilities() + ToolCapability.USER_INTERACTION
        if (!capabilities.all { it in allowed }) return false
        // Git write may inspect files and mutate git remotes, but it must not
        // mutate the workspace filesystem (that is WORKSPACE_WRITE / shell).
        if (this == GIT_WRITE &&
            ToolCapability.MUTATING in capabilities &&
            ToolCapability.FILESYSTEM in capabilities
        ) {
            return false
        }
        return true
    }

    fun isAtMost(other: PermissionLevel): Boolean = rank() <= other.rank()

    private fun rank(): Int = when (this) {
        READ_ONLY -> 0
        WORKSPACE_WRITE -> 1
        COMMAND_EXECUTION -> 2
        NETWORK -> 1
        GIT_WRITE -> 1
    }
}

fun PermissionLevel.restrictTo(other: PermissionLevel?): PermissionLevel {
    if (other == null) return this
    return if (other.isAtMost(this)) other else this
}

/** Maps the agent permission ceiling onto Tool System grants. Never expands scope. */
fun PermissionLevel.toToolGrants(): Set<ToolGrant> = when (this) {
    PermissionLevel.READ_ONLY -> setOf(ToolGrant.READ_ONLY)
    PermissionLevel.WORKSPACE_WRITE -> setOf(ToolGrant.READ_ONLY, ToolGrant.WORKSPACE_WRITE)
    PermissionLevel.COMMAND_EXECUTION -> setOf(
        ToolGrant.READ_ONLY,
        ToolGrant.WORKSPACE_WRITE,
        ToolGrant.COMMAND_EXECUTION,
    )
    PermissionLevel.NETWORK -> setOf(ToolGrant.READ_ONLY, ToolGrant.NETWORK)
    PermissionLevel.GIT_WRITE -> setOf(ToolGrant.READ_ONLY, ToolGrant.GIT_WRITE)
}
