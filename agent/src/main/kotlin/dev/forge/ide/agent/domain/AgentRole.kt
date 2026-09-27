package dev.forge.ide.agent.domain

import dev.forge.ide.tools.ToolCapability

enum class AgentRole {
    MAIN,
    EXPLORER,
    RESEARCHER,
    CODER,
    DEBUGGER,
    REVIEWER,
    TESTER,
}

enum class AgentStatus {
    IDLE,
    PLANNING,
    RUNNING,
    WAITING_FOR_SUBAGENT,
    COMPLETED,
    CANCELLED,
    FAILED,
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
        )
        GIT_WRITE -> setOf(
            ToolCapability.READ_ONLY,
            ToolCapability.GIT,
            ToolCapability.MUTATING,
        )
    }

    fun allows(capabilities: Set<ToolCapability>): Boolean {
        if (ToolCapability.CREDENTIALS in capabilities) return false
        if (capabilities.isEmpty()) return true
        val allowed = allowedCapabilities() + ToolCapability.USER_INTERACTION
        return capabilities.all { it in allowed }
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
