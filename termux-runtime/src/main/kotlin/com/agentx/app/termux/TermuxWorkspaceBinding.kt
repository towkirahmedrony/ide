package com.agentx.app.termux

import java.security.MessageDigest

/**
 * Which way a mirrored workspace is allowed to move data.
 *
 * Stated as a type rather than a comment so the UI has to display it and tests can assert it.
 */
enum class MirrorDirection {
    /** Files are copied into the mirror. Edits inside the terminal stay in the mirror. */
    SourceToMirrorOnly,
}

/**
 * How a workspace maps onto the embedded shell.
 *
 * A workspace opened through Android's Storage Access Framework is a `content://` tree with no
 * filesystem path. Pretending otherwise would make `pwd` lie, so a SAF workspace either gets a
 * real mirror inside the Termux home or the shell is rooted somewhere honest — never silently
 * at a path that does not exist.
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
     * against it and do **not** write back to the original tree, which is why [direction] exists
     * and why the UI labels it.
     */
    data class Mirrored(
        val termuxPath: String,
        val source: String,
        val direction: MirrorDirection = MirrorDirection.SourceToMirrorOnly,
        override val displayLocation: String,
    ) : TermuxWorkspaceBinding {
        /** True only when a write-back path is implemented. It is not, so this is always false. */
        val writesBack: Boolean get() = false
    }

    /**
     * The workspace could not be bound, so the shell runs at `$HOME` instead of not at all.
     *
     * An unreadable workspace must not cost the user their terminal: this keeps a usable shell
     * and carries the reason, which the UI shows next to it.
     */
    data class Home(
        val path: String,
        val reason: String,
        override val displayLocation: String,
    ) : TermuxWorkspaceBinding

    /** No shell-usable location exists at all. */
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
     * Where the mirror of the tree identified by [handle] lives.
     *
     * [handle] is normally a `content://` URI from the Storage Access Framework. The directory
     * name is `<readable>-<hash>`: the readable half makes the directory recognisable when
     * someone lists it, the hash half makes the mapping deterministic and collision-free. Two
     * different URIs whose readable halves collide still get different directories, which
     * `safeSegment` alone could not guarantee once it truncates.
     */
    fun mirrorPathForHandle(paths: TermuxPaths, handle: String): String =
        "${paths.workspaces}/${mirrorSegment(handle)}"

    /**
     * A single, safe directory name for [handle].
     *
     * The result contains only `[a-z0-9_-]`, is at most 41 characters, is never `.` or `..`, and
     * is stable for the same input — so it can never traverse out of the workspaces directory
     * however hostile the URI is.
     */
    fun mirrorSegment(handle: String): String {
        val normalized = handle.trim()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(16)

        // A recognisable tail: the last path/document segment, stripped of any query or
        // percent-escapes, then reduced to safe characters.
        val candidate = normalized
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringBefore("://")
            .substringAfter("%2F", missingDelimiterValue = "")
            .ifBlank { normalized.substringBefore('?').substringAfterLast('/') }
            .substringAfter("%3A", missingDelimiterValue = "")

        val readable = candidate
            .map { character -> if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_' }
            .joinToString("")
            .trim('_')
            .lowercase()
            .take(24)

        return if (readable.isEmpty()) digest else "$readable-$digest"
    }

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

        fun homeFallback(reason: String): TermuxWorkspaceBinding =
            fallbackHome(paths = paths, shown = shown, reason = reason, isDirectory = isDirectory)

        val hadPathLikeCandidate = listOfNotNull(handle, displayLocation).any { asFilesystemPath(it) != null }
        if (hadPathLikeCandidate) {
            // A real path was offered but is not readable: a permission problem the user has to
            // fix, but not a reason to take the terminal away from them.
            return homeFallback(
                "The workspace path is not a readable directory from this app, so the shell is " +
                    "running in ${paths.home} instead. Re-pick the folder to grant access again.",
            )
        }

        val mirror = mirrorPathForHandle(paths, handle ?: workspaceId)
        if (isDirectory(mirror)) {
            return TermuxWorkspaceBinding.Mirrored(
                termuxPath = mirror,
                source = shown.ifBlank { "mirrored workspace" },
                direction = MirrorDirection.SourceToMirrorOnly,
                displayLocation = shown.ifBlank { mirror },
            )
        }

        return homeFallback(safReason(shown, mirror))
    }

    /**
     * The binding to fall back to when a workspace cannot be entered or mirrored.
     *
     * Used both by [resolve] and by the caller that runs the mirror: if a copy fails or is
     * cancelled, the terminal keeps working at `$HOME` and the reason travels with it, instead of
     * the user losing the shell they were using.
     *
     * Returns [TermuxWorkspaceBinding.Unavailable] only when `$HOME` itself is unusable, because
     * then there is genuinely nowhere to start a shell.
     */
    fun fallbackHome(
        paths: TermuxPaths,
        shown: String?,
        reason: String,
        isDirectory: (String) -> Boolean,
    ): TermuxWorkspaceBinding {
        val label = shown?.trim().orEmpty()
        val home = paths.home
        return if (isDirectory(home)) {
            TermuxWorkspaceBinding.Home(
                path = home,
                reason = reason,
                displayLocation = label.ifBlank { "home" },
            )
        } else {
            TermuxWorkspaceBinding.Unavailable(
                reason = "$reason $home is not usable either, so no shell can be started.",
                displayLocation = label.ifBlank { "this workspace" },
            )
        }
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
            "path a shell can enter. It can be mirrored into $mirror, where commands run against " +
            "a copy and the original tree is never written back."
    }

    /** Keeps a workspace id usable as a single directory name. */
    fun safeSegment(raw: String): String {
        val cleaned = raw.trim().map { character ->
            if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
        }.joinToString("")
        return cleaned.take(64).ifBlank { "workspace" }
    }
}
