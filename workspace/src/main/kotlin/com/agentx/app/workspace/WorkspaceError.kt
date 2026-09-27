package com.agentx.app.workspace

import com.agentx.app.core.ForgeResult

/** Stable, coarse-grained categories for workspace failures. */
enum class WorkspaceErrorCode {
    /** The opaque handle is empty or malformed. */
    INVALID_HANDLE,

    /** The selected workspace no longer exists or was removed. */
    WORKSPACE_NOT_FOUND,

    /** A directory operation targeted a file, or vice versa. */
    NOT_A_DIRECTORY,

    /** A file operation targeted a directory. */
    NOT_A_FILE,

    /** Read or write access to the workspace was denied or revoked. */
    PERMISSION_DENIED,

    /** The requested path does not exist inside the workspace. */
    NOT_FOUND,

    /** The destination already exists. */
    ALREADY_EXISTS,

    /** The path is structurally invalid (empty segment, control characters). */
    INVALID_PATH,

    /** Absolute paths are never accepted inside a workspace. */
    ABSOLUTE_PATH,

    /** The path tried to escape the workspace root. */
    PATH_TRAVERSAL,

    /** The file is larger than the runtime is willing to read into memory. */
    FILE_TOO_LARGE,

    /** The file is not text (binary, media, archive, …) and cannot be edited. */
    UNSUPPORTED_FILE_TYPE,

    /** The backing storage reported an I/O failure. */
    IO_FAILED,

    /** The backend does not support the requested operation. */
    UNSUPPORTED_OPERATION,

    /** Process execution is intentionally disabled until the security policy lands. */
    PROCESS_EXECUTION_UNAVAILABLE,

    UNKNOWN,
}

/**
 * A structured workspace failure. Domain code returns these through
 * [WorkspaceResult] instead of throwing, so callers never have to parse
 * messages or catch platform exceptions.
 */
data class WorkspaceError(
    val code: WorkspaceErrorCode,
    val message: String,
    val path: String? = null,
    val cause: Throwable? = null,
) {
    /**
     * Short, user-facing explanation. Safe to display in the IDE and free of
     * any device-specific locations.
     */
    val userMessage: String
        get() = when (code) {
            WorkspaceErrorCode.INVALID_HANDLE -> "The selected workspace location is not valid."
            WorkspaceErrorCode.WORKSPACE_NOT_FOUND ->
                "This workspace is no longer available. It may have been moved or deleted."
            WorkspaceErrorCode.PERMISSION_DENIED ->
                "Access to this workspace was denied or revoked. Open it again to restore access."
            WorkspaceErrorCode.NOT_FOUND -> "The requested item was not found."
            WorkspaceErrorCode.NOT_A_DIRECTORY -> "That item is not a folder."
            WorkspaceErrorCode.NOT_A_FILE -> "That item is not a file."
            WorkspaceErrorCode.ALREADY_EXISTS -> "An item with that name already exists."
            WorkspaceErrorCode.INVALID_PATH -> "That path is not valid inside this workspace."
            WorkspaceErrorCode.ABSOLUTE_PATH -> "Only paths inside the workspace are allowed."
            WorkspaceErrorCode.PATH_TRAVERSAL -> "That path points outside the workspace."
            WorkspaceErrorCode.FILE_TOO_LARGE -> "This file is too large to open in the editor."
            WorkspaceErrorCode.UNSUPPORTED_FILE_TYPE ->
                "This file is not a text file, so it cannot be opened in the editor."
            WorkspaceErrorCode.IO_FAILED -> "The workspace could not be read or written."
            WorkspaceErrorCode.UNSUPPORTED_OPERATION -> "This operation is not supported here."
            WorkspaceErrorCode.PROCESS_EXECUTION_UNAVAILABLE ->
                "Command execution is not enabled yet."
            WorkspaceErrorCode.UNKNOWN -> message.ifBlank { "Something went wrong." }
        }
}

/** Result type for all workspace operations. */
typealias WorkspaceResult<T> = ForgeResult<T, WorkspaceError>

/** Convenience constructor for a failure with an explicit code and message. */
fun workspaceError(
    code: WorkspaceErrorCode,
    message: String,
    path: String? = null,
    cause: Throwable? = null,
): WorkspaceError = WorkspaceError(code = code, message = message, path = path, cause = cause)
