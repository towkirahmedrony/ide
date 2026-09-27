package dev.forge.ide.tools

/**
 * A request for the user to approve a tool call. Produced by the router when
 * the permission policy returns ASK. No UI exists yet; a future approval dialog
 * will render this and hand back a [ToolApproval].
 */
data class ToolApprovalRequest(
    val toolName: String,
    val input: ToolInput,
    val reason: String,
)

/** Response to a [ToolApprovalRequest]. */
data class ToolApproval(
    val approved: Boolean,
    val reason: String? = null,
) {
    companion object {
        fun granted(reason: String? = null): ToolApproval = ToolApproval(approved = true, reason = reason)

        fun denied(reason: String? = null): ToolApproval = ToolApproval(approved = false, reason = reason)
    }
}

/**
 * Ambient information available to a tool while it executes. Deliberately free
 * of UI, model, and Android types so it can be constructed anywhere. It must
 * never carry secrets.
 */
data class ToolExecutionContext(
    val sessionId: String? = null,
    val agentId: String? = null,
    val workspaceId: String? = null,

    /** Present only when a previously-required approval was decided. */
    val approval: ToolApproval? = null,
    val attributes: JsonObject = emptyMap(),
) {
    companion object {
        val EMPTY = ToolExecutionContext()
    }
}
