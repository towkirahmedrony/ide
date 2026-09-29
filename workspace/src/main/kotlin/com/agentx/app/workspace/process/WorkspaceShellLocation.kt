package com.agentx.app.workspace.process

/**
 * How a workspace can (or cannot) be used as a shell working directory.
 *
 * SAF `content://` trees are not filesystem paths. Silently mapping them to a
 * fake `/storage/...` location would make `cd`/`pwd` lie; the terminal must
 * surface that limitation instead.
 */
sealed interface WorkspaceShellLocation {
    data class Filesystem(val path: String) : WorkspaceShellLocation

    data class Unavailable(val reason: String, val displayLocation: String) : WorkspaceShellLocation
}

/**
 * Resolves a workspace handle / display location into a shell-usable path.
 *
 * Only real filesystem paths are accepted. `content://` and other URI schemes
 * produce [WorkspaceShellLocation.Unavailable].
 */
object WorkspaceShellLocations {

    fun resolve(handle: String?, displayLocation: String?): WorkspaceShellLocation {
        val candidates = listOfNotNull(handle?.trim()?.takeIf { it.isNotEmpty() }, displayLocation?.trim()?.takeIf { it.isNotEmpty() })
        for (candidate in candidates) {
            val filesystem = asFilesystemPath(candidate)
            if (filesystem != null) return WorkspaceShellLocation.Filesystem(filesystem)
        }
        val shown = displayLocation?.trim().orEmpty().ifBlank { handle?.trim().orEmpty() }
        return WorkspaceShellLocation.Unavailable(
            reason = safLimitationMessage(shown),
            displayLocation = shown.ifBlank { "this workspace" },
        )
    }

    fun asFilesystemPath(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val scheme = schemeOf(value)
        if (scheme != null && scheme != "file") return null
        val path = if (scheme == "file") value.substringAfter("file://") else value
        return if (path.startsWith("/")) path else null
    }

    private fun schemeOf(value: String): String? {
        val separator = value.indexOf("://")
        if (separator <= 0) return null
        val scheme = value.substring(0, separator)
        return if (scheme.all { it.isLetter() }) scheme.lowercase() else null
    }

    private fun safLimitationMessage(shown: String): String {
        val label = shown.ifBlank { "this workspace" }
        return "This workspace ($label) is opened through Android Storage Access " +
            "Framework and has no filesystem path the shell can use. Commands still " +
            "run, but they start in the app's private directory instead of the " +
            "project folder. Pick a folder the device exposes as a real path to " +
            "start the terminal there."
    }
}
