package com.agentx.app.termux

/**
 * How a workspace maps onto the embedded shell.
 *
 * A workspace opened through Android's Storage Access Framework is a `content://` tree with no
 * filesystem path. Pretending otherwise would make `pwd` lie, so a SAF workspace either gets a
 * real mirror inside the Termux home or is reported as unreachable — never silently substituted.
 */
sealed interface TermuxWorkspaceBinding {

    /** The shell can `cd` straight into [path]. */
    data class Direct(
        val path: String,
        override val displayLocation: String,
    ) : TermuxWorkspaceBinding

    /**
     * The workspace was materialised under the Termux home at [termuxPath].
     *
     * [source] is the SAF location it was mirrored from. The mirror is a copy: commands run
     * against it and do **not** write back to the original tree, which is why the UI labels it.
     */
    data class Mirrored(
        val termuxPath: String,
        val source: String,
        override val displayLocation: String,
    ) : TermuxWorkspaceBinding

    /** No shell-usable location exists for this workspace. */
    data class Unavailable(
        val reason: String,
        override val displayLocation: String,
    ) : TermuxWorkspaceBinding

    val displayLocation: String
}

/**
 * Resolves a workspace into a shell binding.
 *
 * Pure decision logic with injected filesystem probes, so every branch is unit tested.
 */
object TermuxWorkspaceBindings {

    /** Where a mirrored workspace for [workspaceId] lives. Bounded to a safe directory name. */
    fun mirrorPath(paths: TermuxPaths, workspaceId: String): String =
        "${paths.workspaces}/${safeSegment(workspaceId)}"

    /**
     * @param handle a filesystem path or `content://` URI describing the workspace, if known.
     * @param displayLocation what the workspace runtime shows the user.
     * @param workspaceId stable workspace identity, used for the mirror directory.
     * @param paths layout of the embedded runtime.
     * @param isDirectory probes whether a path is a readable directory.
     */
    fun resolve(
        handle: String?,
        displayLocation: String?,
        workspaceId: String,
        paths: TermuxPaths,
        isDirectory: (String) -> Boolean,
    ): TermuxWorkspaceBinding {
        val shown = displayLocation?.trim().orEmpty().ifBlank { handle?.trim().orEmpty() }

        val filesystem = listOfNotNull(handle, displayLocation)
            .mapNotNull { asFilesystemPath(it) }
            .firstOrNull { isDirectory(it) }

        if (filesystem != null) {
            return TermuxWorkspaceBinding.Direct(path = filesystem, displayLocation = shown.ifBlank { filesystem })
        }

        val hadPathLikeCandidate = listOfNotNull(handle, displayLocation).any { asFilesystemPath(it) != null }
        if (hadPathLikeCandidate) {
            // A real path was offered but is not readable: that is a permission problem the
            // user has to fix, not a SAF workspace.
            return TermuxWorkspaceBinding.Unavailable(
                reason = "The workspace path is not a readable directory from this app. " +
                    "Re-pick the folder so the app is granted access again.",
                displayLocation = shown.ifBlank { "this workspace" },
            )
        }

        val mirror = mirrorPath(paths, workspaceId)
        if (isDirectory(mirror)) {
            return TermuxWorkspaceBinding.Mirrored(
                termuxPath = mirror,
                source = shown.ifBlank { "mirrored workspace" },
                displayLocation = shown.ifBlank { mirror },
            )
        }

        return TermuxWorkspaceBinding.Unavailable(
            reason = safReason(shown, mirror),
            displayLocation = shown.ifBlank { "this workspace" },
        )
    }

    /** Accepts plain absolute paths and `file://` URIs; rejects every other scheme. */
    fun asFilesystemPath(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val scheme = schemeOf(value)
        if (scheme != null && scheme != "file") return null
        val path = if (scheme == "file") value.substringAfter("file://") else value
        return if (path.startsWith("/")) path.trimEnd('/').ifEmpty { "/" } else null
    }

    private fun schemeOf(value: String): String? {
        val separator = value.indexOf("://")
        if (separator <= 0) return null
        val scheme = value.substring(0, separator)
        return if (scheme.all { it.isLetter() }) scheme.lowercase() else null
    }

    private fun safReason(shown: String, mirror: String): String {
        val label = shown.ifBlank { "This workspace" }
        return "$label is opened through Android Storage Access Framework and has no filesystem " +
            "path a shell can enter, so the terminal cannot start there. It can be mirrored into " +
            "$mirror, where commands run against a copy; the original tree is not written back."
    }

    /** Keeps a workspace id usable as a single directory name. */
    fun safeSegment(raw: String): String {
        val cleaned = raw.trim().map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
        }.joinToString("")
        return cleaned.take(64).ifBlank { "workspace" }
    }
}
