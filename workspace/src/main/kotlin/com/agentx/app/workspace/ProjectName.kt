package com.agentx.app.workspace

import com.agentx.app.core.failure
import com.agentx.app.core.success

/**
 * Validation for a manually created project's name.
 *
 * A new project becomes a single directory inside AgentX-managed storage, so its name must be
 * exactly one safe path segment. Everything that could point outside that directory — an empty
 * name, `.`, `..`, a path separator, an absolute prefix, or a control character — is rejected
 * with a structured error instead of being "cleaned up", so a caller can never ask for a location
 * it did not name.
 */
object ProjectName {

    /** Longest accepted name, comfortably inside every common filesystem's segment limit. */
    const val MAX_LENGTH: Int = 100

    /** A single safe directory name, or a structured failure describing why it is not one. */
    fun validate(raw: String): WorkspaceResult<String> {
        val name = raw.trim()
        if (name.isEmpty()) return invalid("Enter a project name.")
        if (name.all { it == '.' }) {
            return invalid("A project name cannot be \"$name\".")
        }
        if (name.length > MAX_LENGTH) {
            return invalid("A project name can be at most $MAX_LENGTH characters.")
        }
        if (name.any { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }) {
            return invalid("A project name cannot contain slashes or control characters.")
        }
        return success(name)
    }

    private fun invalid(message: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(code = WorkspaceErrorCode.INVALID_PROJECT_NAME, message = message))
}
