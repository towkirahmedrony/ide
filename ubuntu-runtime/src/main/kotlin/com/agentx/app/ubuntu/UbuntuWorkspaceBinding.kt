package com.agentx.app.ubuntu

/**
 * How an AgentX project is exposed to the guest.
 *
 * The project is never moved into the rootfs. A real host directory (a repository AgentX cloned
 * into its managed storage, or a folder picked from phone storage) is bound straight into the
 * guest at [ProotCommand.GUEST_PROJECT_ROOT].
 *
 * A folder picked through the Android Storage Access Framework keeps its files where they are:
 * the persisted `content://` tree URI is resolved back to the phone-storage path it names
 * (`primary:Projects/app` -> `/storage/emulated/0/Projects/app`, `raw:/storage/...` -> that
 * path) and *that* directory is bound. Nothing is copied. Only when no filesystem path can be
 * derived, or the app cannot read it, does the binding fall back to the guest home with a reason.
 */
sealed interface UbuntuProjectBinding {

    /** The guest directory a shell should start in. */
    val guestPath: String

    data class Direct(override val hostPath: String) : UbuntuProjectBinding {
        override val guestPath: String get() = ProotCommand.GUEST_PROJECT_ROOT
    }

    /** Nothing is bound, so the shell runs in the guest home; [reason] is shown by the UI. */
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
     * When [handle] is a `content://` tree, [displayLocation] is presentation text only and is
     * never treated as a filesystem path: a SAF label such as `/storage/emulated/0/app` (from a
     * `raw:` tree id) must not be mistaken for a real path the app was opened with.
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
        val named = listOfNotNull(handle, displayLocation).any { it.isNotBlank() }
        if (!named) return UbuntuProjectBinding.Home(NO_PROJECT_REASON)

        val rawHandles = listOfNotNull(handle, displayLocation)
        val handleIsTree = handle?.trim()?.startsWith(CONTENT_PREFIX) == true
        val plainSources = if (handleIsTree) listOfNotNull(handle) else rawHandles
        val plain = plainSources.mapNotNull(::asFilesystemPath)
        val derived = rawHandles.mapNotNull(::safFilesystemPath)
        val direct = (plain + derived).firstOrNull { isDirectory(it) }
        if (direct != null) return UbuntuProjectBinding.Direct(direct)

        val shown = displayLocation?.trim().orEmpty().ifBlank { handle?.trim().orEmpty() }
        val reason = when {
            plain.isNotEmpty() ->
                "The project path is not a readable directory from this app, so the shell is running " +
                    "in the guest home instead. Check that All files access is granted in Android Settings, then restart the terminal."
            derived.isNotEmpty() ->
                "${shown.ifBlank { "This project folder" }} can only be reached through Android " +
                    "Storage Access Framework and is not readable as a filesystem path from this " +
                    "app, so there is nothing to bind-mount at ${ProotCommand.GUEST_PROJECT_ROOT}: " +
                    "the shell is running in the guest home instead. The project is not copied. " +
                    "Grant the app All files access in Android Settings, then reopen the workspace."
            else ->
                "${shown.ifBlank { "This workspace" }} is opened through Android Storage Access " +
                    "Framework and has no filesystem path that can be bind-mounted into the guest, " +
                    "so the shell is running in the guest home. The project is not copied."
        }
        return UbuntuProjectBinding.Home(reason)
    }

    /** Where [raw] is a directory in the device's shared storage, or null when it is not. */
    fun sharedStorageLocation(raw: String?): String? =
        listOfNotNull(asFilesystemPath(raw), safFilesystemPath(raw))
            .firstOrNull(::isSharedStoragePath)

    /** Whether [raw] names a directory in shared storage. */
    fun isSharedStorageLocation(raw: String?): Boolean = sharedStorageLocation(raw) != null

    private fun isSharedStoragePath(path: String): Boolean =
        listOf(STORAGE_ROOT, PRIMARY_ALIAS, LEGACY_PRIMARY_ALIAS)
            .any { root -> path == root || path.startsWith("$root/") }

    /**
     * The phone-storage path a Storage Access Framework tree URI names, when it has one.
     *
     * - `primary:Projects/app` -> `/storage/emulated/0/Projects/app`
     * - `1234-ABCD:backups` -> `/storage/1234-ABCD/backups`
     * - `raw:/storage/emulated/0/Projects/app` -> that absolute path (external-storage and
     *   downloads providers only)
     *
     * Returns null for every handle this cannot prove is a local path: other authorities (cloud
     * providers, Termux, third-party file managers), tree ids without a volume, and traversal
     * segments (`.`/`..`).
     */
    fun safFilesystemPath(raw: String?): String? {
        val value = raw?.trim()?.substringBefore('#')?.substringBefore('?').orEmpty()
        if (!value.startsWith(CONTENT_PREFIX)) return null
        val rest = value.removePrefix(CONTENT_PREFIX)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val authority = rest.substring(0, slash)
        if (authority != EXTERNAL_STORAGE_AUTHORITY && authority != DOWNLOADS_AUTHORITY) return null
        val segments = rest.substring(slash + 1).split('/')
        val treeIndex = segments.indexOf("tree")
        if (treeIndex < 0) return null
        val treeId = segments.getOrNull(treeIndex + 1)?.let(::percentDecode) ?: return null
        if (treeId.isEmpty()) return null
        val separator = treeId.indexOf(':')
        if (separator <= 0) return null
        val volume = treeId.substring(0, separator)
        val relative = treeId.substring(separator + 1)

        if (volume == RAW_VOLUME) return rawPath(relative)
        if (authority != EXTERNAL_STORAGE_AUTHORITY) return null

        val base = when {
            volume == PRIMARY_VOLUME -> PRIMARY_STORAGE_ROOT
            volume.all { it.isLetterOrDigit() || it == '-' || it == '_' } -> "/storage/$volume"
            else -> return null
        }
        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.any(::isUnsafeSegment)) return null
        return if (parts.isEmpty()) base else parts.joinToString("/", prefix = "$base/")
    }

    /** An absolute path carried by a `raw:` tree id, or null when it is not a safe absolute path. */
    private fun rawPath(path: String): String? {
        if (!path.startsWith("/")) return null
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any(::isUnsafeSegment)) return null
        return parts.joinToString("/", prefix = "/")
    }

    private fun isUnsafeSegment(part: String): Boolean =
        part == "." || part == ".." || part.contains('\u0000') || part.contains('\n')

    /** Percent-decodes one URI path segment as UTF-8, leaving invalid escapes intact. */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%' && index + 2 < value.length) {
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high >= 0 && low >= 0) {
                    out.write(high * 16 + low)
                    index += 3
                    continue
                }
            }
            val codePoint = value.codePointAt(index)
            out.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
            index += Character.charCount(codePoint)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
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

    private const val CONTENT_PREFIX = "content://"

    /** The reason a terminal reports when there is no project to work on. */
    const val NO_PROJECT_REASON: String =
        "No project is open, so the shell is running in the guest home with nothing mounted at " +
            "${ProotCommand.GUEST_PROJECT_ROOT} — open a project to work in it from the terminal"

    /** The provider `ACTION_OPEN_DOCUMENT_TREE` answers with for local storage. */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Downloads provider; only its `raw:` tree ids name a real path. */
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

    private const val PRIMARY_VOLUME = "primary"

    private const val RAW_VOLUME = "raw"

    private const val PRIMARY_STORAGE_ROOT = "/storage/emulated/0"

    /** The parent of every Android volume, removable ones included. */
    private const val STORAGE_ROOT = "/storage"

    /** Android's alias for the primary shared volume. */
    private const val PRIMARY_ALIAS = "/sdcard"

    /** The pre-multi-user alias of the same volume. */
    private const val LEGACY_PRIMARY_ALIAS = "/mnt/sdcard"
}
