package com.agentx.app.tools

/**
 * Outcome of evaluating a tool call against the permission policy.
 *
 * Dangerous operations (file deletion, shell commands, `git push`, credential
 * access, external network actions) can later be forced to ASK or DENY by the
 * policy without ever changing the [Tool] interface.
 */
enum class ToolPermissionDecision {
    /** Run without asking. */
    ALLOW,

    /** Pause and ask the user before running. */
    ASK,

    /** Never run. */
    DENY,
}

/** A permission decision plus the reason behind it. */
data class ToolPermission(
    val decision: ToolPermissionDecision,
    val reason: String? = null,
) {
    companion object {
        fun allow(reason: String? = null): ToolPermission = ToolPermission(ToolPermissionDecision.ALLOW, reason)

        fun ask(reason: String? = null): ToolPermission = ToolPermission(ToolPermissionDecision.ASK, reason)

        fun deny(reason: String? = null): ToolPermission = ToolPermission(ToolPermissionDecision.DENY, reason)
    }
}

/** Everything a policy may inspect before a tool runs. */
data class ToolPermissionRequest(
    val tool: ToolDefinition,
    val input: ToolInput,
    val context: ToolExecutionContext,
)

/**
 * Single-method port for deciding whether a tool call may proceed. Rules can
 * grow (per-agent policy, workspace policy, dangerous capabilities, ...)
 * without touching [Tool] or [ToolRouter].
 */
fun interface ToolPermissionPolicy {
    fun evaluate(request: ToolPermissionRequest): ToolPermission

    companion object {
        /** Applies the permission each [ToolDefinition] declares. */
        fun default(): ToolPermissionPolicy = DefaultToolPermissionPolicy

        /** Allows everything; useful for tests and trusted contexts. */
        fun allowAll(): ToolPermissionPolicy = AllowAllToolPermissionPolicy
    }
}

/** Allows every request. Never use for tools with real side effects. */
object AllowAllToolPermissionPolicy : ToolPermissionPolicy {
    override fun evaluate(request: ToolPermissionRequest): ToolPermission =
        ToolPermission.allow("allow-all policy")
}

/** Applies the permission declared by the tool's [ToolDefinition]. */
object DefaultToolPermissionPolicy : ToolPermissionPolicy {
    override fun evaluate(request: ToolPermissionRequest): ToolPermission = when (request.tool.permission) {
        ToolPermissionDecision.ALLOW -> ToolPermission.allow("Tool declares ALLOW")
        ToolPermissionDecision.ASK -> ToolPermission.ask("Tool '${request.tool.name}' requires approval")
        ToolPermissionDecision.DENY -> ToolPermission.deny("Tool '${request.tool.name}' is disabled by policy")
    }
}
