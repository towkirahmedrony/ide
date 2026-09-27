package com.agentx.app.workspace

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success

/**
 * Helpers for workspace-relative paths.
 *
 * A path is always relative to the opened workspace root, never absolute, and
 * can never escape it. Every filesystem operation runs its input through
 * [normalize], so a malicious or buggy caller cannot reach outside the folder
 * the user selected.
 */
object WorkspacePath {

    /** The workspace root. */
    const val ROOT: String = ""

    private const val SEPARATOR = '/'

    /**
     * Normalizes [raw] into a clean relative path, or returns a structured
     * failure. `.` segments are dropped, empty segments collapsed, and any
     * `..` segment, absolute prefix, or control character is rejected.
     */
    fun normalize(raw: String): WorkspaceResult<String> {
        val candidate = raw.replace('\\', SEPARATOR).trim()
        if (candidate.isEmpty() || candidate == "." || candidate == SEPARATOR.toString()) {
            return success(ROOT)
        }
        if (candidate.startsWith(SEPARATOR)) {
            return failure(
                WorkspaceError(
                    code = WorkspaceErrorCode.ABSOLUTE_PATH,
                    message = "Absolute paths are not allowed inside a workspace.",
                    path = raw,
                ),
            )
        }

        val segments = candidate.split(SEPARATOR)
        val clean = ArrayList<String>(segments.size)
        for (segment in segments) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> return failure(
                    WorkspaceError(
                        code = WorkspaceErrorCode.PATH_TRAVERSAL,
                        message = "Path traversal outside the workspace is not allowed.",
                        path = raw,
                    ),
                )
                segment.hasIllegalCharacters() -> return failure(
                    WorkspaceError(
                        code = WorkspaceErrorCode.INVALID_PATH,
                        message = "The path contains illegal characters.",
                        path = raw,
                    ),
                )
                else -> clean += segment
            }
        }
        return success(clean.joinToString(SEPARATOR.toString()))
    }

    /**
     * Builds the path of a child entry named [name] under [parent]. [name] must
     * be a single path segment.
     */
    fun child(parent: String, name: String): WorkspaceResult<String> {
        if (name.isBlank() || name == "." || name == "..") {
            return failure(
                WorkspaceError(
                    code = WorkspaceErrorCode.INVALID_PATH,
                    message = "The name is not valid.",
                    path = name,
                ),
            )
        }
        if (name.indexOf(SEPARATOR) >= 0 || name.indexOf('\\') >= 0 || name.hasIllegalCharacters()) {
            return failure(
                WorkspaceError(
                    code = WorkspaceErrorCode.INVALID_PATH,
                    message = "The name may not contain path separators.",
                    path = name,
                ),
            )
        }
        val base = when (val normalized = normalize(parent)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return normalized
        }
        return success(if (base.isEmpty()) name else "$base$SEPARATOR$name")
    }

    /** The final segment of [path]; the whole path when it has no separator. */
    fun name(path: String): String = path.substringAfterLast(SEPARATOR, path)

    /** The parent of [path], or [ROOT] when it has no parent. */
    fun parent(path: String): String = path.substringBeforeLast(SEPARATOR, ROOT)

    /** Whether [path] refers to the workspace root. */
    fun isRoot(path: String): Boolean = path.isEmpty()

    private fun String.hasIllegalCharacters(): Boolean = any { it == '\u0000' || it.isISOControl() }
}
