package com.agentx.app.ubuntu

/**
 * How an AgentX project is exposed to the guest.
 *
 * The project is never moved into the rootfs. A real host directory — a repository AgentX
 * cloned into its managed storage, or an existing folder picked from phone storage — is bound
 * straight into the guest at [ProotCommand.GUEST_PROJECT_ROOT], so `touch test.txt` inside the
 * terminal writes the same file the IDE's Files tree and editor read.
 *
 * A folder picked through the Android Storage Access Framework keeps its files in their original
 * location: the persisted `content://` tree URI is resolved back to the phone-storage path it
 * names (`primary:Projects/app` → `/storage/emulated/0/Projects/app`) and *that* directory is
 * bound — nothing is copied into app storage. Only when no filesystem path can be derived, or
 * the app cannot read it as a path, does the binding fall back to the guest home with a reason
 * the UI can show — it is never presented as a directory it is not.
 */
sealed interface UbuntuProjectBinding {

    /** The guest directory a shell should start in. */
    val guestPath: String

    data class Direct(override val hostPath: String) : UbuntuProjectBinding {
        override val guestPath: String get() = ProotCommand.GUEST_PROJECT_ROOT
    }

    /**
     * Nothing is bound, so the shell runs in the guest home instead of not at all.
     *
     * Two situations reach this: no project is open, and a project whose directory cannot be reached
     * as a filesystem path. Both keep a usable shell and carry [reason], which the terminal shows —
     * an unreadable or absent project must not cost the user the terminal, and it must not be
     * presented as a directory it is not.
     */
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
     * Both project sources reach `/workspace` the same way: a real host path is used as-is, and
     * a SAF tree is resolved to the phone-storage path it names before being probed. Nothing is
     * ever copied into app storage here — when the original folder cannot be read as a path, the
     * shell falls back to the guest home with the reason instead.
     *
     * With no project at all there is nothing to bind and the binding says so in those words. It is
     * deliberately not an empty bind of some convenient directory: a terminal that mounts an
     * arbitrary folder under `/workspace` looks like a working project shell while being about
     * something the user never opened.
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
        // Direct paths win over SAF-derived ones: a plain path is what the workspace was opened
        // with, while the derived path is how a `content://` tree is translated.
        val plain = rawHandles.mapNotNull(::asFilesystemPath)
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

    /**
     * The phone-storage path a Storage Access Framework tree URI names, when it has one.
     *
     * The system folder picker (`ACTION_OPEN_DOCUMENT_TREE`) answers with a tree over the
     * external-storage provider, and its tree document id is a real path in disguise:
     * `primary:Projects/app` is `/storage/emulated/0/Projects/app`, and `1234-ABCD:backups` is
     * `/storage/1234-ABCD/backups`. Deriving that path is what lets an already-selected folder be
     * bind-mounted in place — the original files stay where they are and are never copied.
     *
     * Returns null for every handle this cannot prove is a local path: other authorities (cloud
     * providers, third-party file managers), tree ids without a volume, and traversal segments
     * (`.`/`..`) are refused rather than guessed at.
     */
    fun safFilesystemPath(raw: String?): String? {
        val value = raw?.trim()?.substringBefore('#')?.substringBefore('?').orEmpty()
        if (!value.startsWith(CONTENT_PREFIX)) return null
        val rest = value.removePrefix(CONTENT_PREFIX)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val authority = rest.substring(0, slash)
        if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
        // URI path: /tree/<tree-document-id>[/document/<document-id>] — the tree id names the
        // picked folder itself; the document segment (when present) is ignored.
        val segments = rest.substring(slash + 1).split('/')
        val treeIndex = segments.indexOf("tree")
        if (treeIndex < 0) return null
        val treeId = segments.getOrNull(treeIndex + 1)?.let(::percentDecode) ?: return null
        if (treeId.isEmpty()) return null
        val separator = treeId.indexOf(':')
        if (separator <= 0) return null
        val volume = treeId.substring(0, separator)
        val base = when {
            volume == PRIMARY_VOLUME -> PRIMARY_STORAGE_ROOT
            volume.all { it.isLetterOrDigit() || it == '-' || it == '_' } -> "/storage/$volume"
            else -> return null
        }
        val parts = treeId.substring(separator + 1).split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." || it.contains('\u0000') || it.contains('\n') }) {
            return null
        }
        return if (parts.isEmpty()) base else parts.joinToString("/", prefix = "$base/")
    }

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

    /**
     * The reason a terminal reports when there is no project to work on.
     *
     * A terminal with no project is not an error — it is a shell in the guest home, and the runtime
     * is installed and working — but it is not the project shell either, so it says which of the two
     * the user is looking at instead of quietly mounting something. It is one sentence on purpose:
     * the terminal header shows the first sentence of a note, and what to do next is the most useful
     * thing that sentence can carry.
     */
    const val NO_PROJECT_REASON: String =
        "No project is open, so the shell is running in the guest home with nothing mounted at " +
            "${ProotCommand.GUEST_PROJECT_ROOT} — open a project to work in it from the terminal"

    /** The provider `ACTION_OPEN_DOCUMENT_TREE` answers with for local storage. */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    private const val PRIMARY_VOLUME = "primary"

    private const val PRIMARY_STORAGE_ROOT = "/storage/emulated/0"
}
