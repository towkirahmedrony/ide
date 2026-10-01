package com.agentx.app.ubuntu

/**
 * How an AgentX project is exposed to the guest.
 *
 * The project is never moved into the rootfs. A real host directory is bound straight into the
 * guest at [ProotCommand.GUEST_PROJECT_ROOT], so `touch test.txt` inside the terminal writes
 * the same file the IDE's Files tree and editor read. A `content://` tree has no POSIX path, so
 * it falls back to the guest home with a reason the UI can show — it is never presented as a
 * directory it is not. (Materialising a SAF tree is the legacy runtime's mirror path; the new
 * runtime does not silently duplicate a project.)
 */
sealed interface UbuntuProjectBinding {

    /** The guest directory a shell should start in. */
    val guestPath: String

    data class Direct(val hostPath: String) : UbuntuProjectBinding {
        override val guestPath: String get() = ProotCommand.GUEST_PROJECT_ROOT
    }

    data class Home(val reason: String) : UbuntuProjectBinding {
        override val guestPath: String get() = ProotCommand.GUEST_HOME
    }

    /** The host path to bind, or null when nothing is bound. */
    val hostPath: String?
        get() = (this as? Direct)?.hostPath
}

object UbuntuProjectBindings {

    /**
     * Resolves a workspace handle into a binding.
     *
     * @param handle a filesystem path or `content://` URI, if known.
     * @param displayLocation what the workspace runtime shows the user.
     * @param isDirectory probes whether a host path is a readable directory.
     */
    fun resolve(
        handle: String?,
        displayLocation: String?,
        isDirectory: (String) -> Boolean,
    ): UbuntuProjectBinding {
        val candidates = listOfNotNull(handle, displayLocation)
            .mapNotNull(::asFilesystemPath)
        val direct = candidates.firstOrNull { isDirectory(it) }
        if (direct != null) return UbuntuProjectBinding.Direct(direct)

        val shown = displayLocation?.trim().orEmpty().ifBlank { handle?.trim().orEmpty() }
        val reason = if (candidates.isNotEmpty()) {
            "The project path is not a readable directory from this app, so the shell is running " +
                "in the guest home instead. Re-pick the folder to grant access again."
        } else {
            "${shown.ifBlank { "This workspace" }} is opened through Android Storage Access " +
                "Framework and has no filesystem path that can be bind-mounted into the guest, " +
                "so the shell is running in the guest home. The project is not copied."
        }
        return UbuntuProjectBinding.Home(reason)
    }

    /** Accepts absolute paths and `file://` URIs; rejects every other scheme. */
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
}
