package com.agentx.app.termux

/**
 * How a terminal session is named after the project it belongs to.
 *
 * A session key says which project a shell is for, so the session manager can answer the only
 * question a project switch has to ask: *does this shell still belong to the project that is now
 * active?* Keys are built and compared here instead of by string concatenation at the call site,
 * because a key built in one place and matched in another — with a suffix only one of them knew
 * about — is exactly how a shell survives the project it was opened for and keeps running against
 * the previous project's `/workspace`.
 *
 * `TermuxSessionManager` keys every session by this value: a second call for the same key reuses
 * the running shell rather than starting another one, and closing a project's sessions is a
 * membership test against the key.
 */
object TerminalProjectKeys {

    /**
     * Marker for the additional terminal a project can have open beside its first one.
     *
     * A suffix on the workspace id rather than a separate namespace, so a key always starts from the
     * project it belongs to and cannot be mistaken for another project's session.
     */
    const val SECONDARY_SUFFIX: String = "::2"

    /** The key of a project's first terminal. */
    fun primary(workspaceId: String): String = workspaceId

    /** The key of a project's additional terminal. */
    fun secondary(workspaceId: String): String = workspaceId + SECONDARY_SUFFIX

    /** The key of one of a project's terminals. */
    fun forSession(workspaceId: String, secondary: Boolean): String =
        if (secondary) secondary(workspaceId) else primary(workspaceId)

    /**
     * Whether [workspaceKey] names a terminal of the project [workspaceId].
     *
     * Exact matching, never a prefix test: one workspace id can be a prefix of another, and a prefix
     * match would let the project `abc` claim the shells of the project `abcd` — which is how a
     * switch could leave a stranger's shell running against the new project's `/workspace`.
     */
    fun belongsTo(workspaceKey: String, workspaceId: String): Boolean =
        workspaceKey == primary(workspaceId) || workspaceKey == secondary(workspaceId)
}
