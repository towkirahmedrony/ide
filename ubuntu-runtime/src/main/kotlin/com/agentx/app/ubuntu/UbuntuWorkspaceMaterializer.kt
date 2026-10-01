package com.agentx.app.ubuntu

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.agentx.app.termux.FileMirrorSink
import com.agentx.app.termux.MirrorEntry
import com.agentx.app.termux.MirrorOutcome
import com.agentx.app.termux.MirrorSource
import com.agentx.app.termux.TermuxWorkspaceBindings
import com.agentx.app.termux.TermuxWorkspaceMirror
import java.io.File
import java.io.FileNotFoundException

/** What happened when a project was prepared for the guest. */
sealed interface UbuntuWorkspaceMaterialization {

    /** A real directory the guest can bind-mount at [ProotCommand.GUEST_PROJECT_ROOT]. */
    data class Ready(val hostPath: String) : UbuntuWorkspaceMaterialization

    /** The handle is already a usable filesystem path, or there is no project at all. */
    data object NotNeeded : UbuntuWorkspaceMaterialization

    /** The project could not be prepared; the shell runs in the guest home instead. */
    data class Failed(val reason: String) : UbuntuWorkspaceMaterialization
}

/**
 * Turns a workspace into a directory PRoot can bind-mount.
 *
 * A project chosen through Android's Storage Access Framework is a `content://` tree, and a
 * `content://` URI has no POSIX path: handing it to `proot -b` produces a shell rooted nowhere.
 * So the tree is copied into app-private storage first and *that* directory is what gets bound
 * at [ProotCommand.GUEST_PROJECT_ROOT]. PRoot never sees a `content://` URI.
 *
 * The copy is a one-way mirror, exactly like the legacy runtime's: commands run in the terminal
 * against the copy, and nothing is written back to the original tree. It is cached per process
 * and marked on disk, so reopening a workspace reuses the same copy instead of re-reading the
 * whole project — and so a shell cannot silently look at a half-finished copy.
 *
 * The reusable copy engine is [TermuxWorkspaceMirror], which is not bootstrap code: it is the
 * bounded, hostile-name-checked SAF copier. Sharing it does not pull in the legacy prefix, its
 * catalog or its installer.
 */
class UbuntuWorkspaceMaterializer(
    context: Context,
    private val layout: NativeRuntimeLayout,
) {

    private val appContext = context.applicationContext

    /** Handles already prepared in this process, so a reopened tab is not recopied. */
    private val prepared = LinkedHashMap<String, String>()

    fun materialize(handle: String?, workspaceId: String): UbuntuWorkspaceMaterialization {
        val value = handle?.trim().orEmpty()
        if (value.isEmpty()) return UbuntuWorkspaceMaterialization.NotNeeded
        // A path the app can already read is bound directly; only SAF trees need materialising.
        if (!value.startsWith(CONTENT_SCHEME)) return UbuntuWorkspaceMaterialization.NotNeeded

        prepared[value]?.let { path ->
            if (File(path).isDirectory) return UbuntuWorkspaceMaterialization.Ready(path)
        }

        val destination = File(layout.workspaces, TermuxWorkspaceBindings.mirrorSegment(value))
        if (isCompleteCopy(destination)) {
            prepared[value] = destination.absolutePath
            return UbuntuWorkspaceMaterialization.Ready(destination.absolutePath)
        }

        val root = runCatching { DocumentFile.fromTreeUri(appContext, Uri.parse(value)) }.getOrNull()
            ?: return unreadable()
        if (!runCatching { root.isDirectory }.getOrDefault(false)) {
            return unreadable()
        }

        if (!destination.mkdirs() && !destination.isDirectory) {
            return UbuntuWorkspaceMaterialization.Failed(
                "Could not create ${destination.absolutePath} for the project copy, so the " +
                    "shell is running in the guest home.",
            )
        }

        val outcome = try {
            TermuxWorkspaceMirror.mirror(
                source = SafMirrorSource(appContext, root),
                sink = FileMirrorSink(destination),
            )
        } catch (error: Exception) {
            discard(destination)
            return UbuntuWorkspaceMaterialization.Failed(
                "Copying $workspaceId into the runtime failed (${error.javaClass.simpleName}), " +
                    "so the shell is running in the guest home.",
            )
        }

        return when (outcome) {
            is MirrorOutcome.Completed -> {
                writeCompletionMarker(destination)
                prepared[value] = destination.absolutePath
                UbuntuWorkspaceMaterialization.Ready(destination.absolutePath)
            }

            is MirrorOutcome.Cancelled -> {
                discard(destination)
                UbuntuWorkspaceMaterialization.Failed(
                    "Copying $workspaceId stopped after ${outcome.files} file(s), so the shell " +
                        "is running in the guest home.",
                )
            }

            is MirrorOutcome.Failed -> {
                discard(destination)
                UbuntuWorkspaceMaterialization.Failed(
                    "Copying $workspaceId into the runtime failed: ${outcome.reason} " +
                        "The shell is running in the guest home.",
                )
            }
        }
    }

    /** Forgets a prepared copy, e.g. after the user re-picked the folder. */
    fun forget(handle: String) {
        prepared.remove(handle.trim())
    }

    private fun unreadable(): UbuntuWorkspaceMaterialization =
        UbuntuWorkspaceMaterialization.Failed(
            "The project folder is no longer readable, so the shell is running in the guest " +
                "home. Re-pick the folder to grant access again.",
        )

    private fun isCompleteCopy(directory: File): Boolean =
        directory.isDirectory && File(directory, MIRROR_MARKER).isFile

    private fun writeCompletionMarker(directory: File) {
        File(directory, MIRROR_MARKER).writeText("ok\n")
    }

    private fun discard(directory: File) {
        runCatching { directory.deleteRecursively() }
    }

    /** A bounded, path-safe reader over a SAF tree. */
    private class SafMirrorSource(
        context: Context,
        private val root: DocumentFile,
    ) : MirrorSource {

        private val resolver = context.applicationContext.contentResolver

        override fun list(relativePath: String): List<MirrorEntry> {
            val directory = resolve(relativePath) ?: return emptyList()
            return directory.listFiles().mapNotNull { child ->
                val name = child.name ?: return@mapNotNull null
                MirrorEntry(
                    name = name,
                    directory = runCatching { child.isDirectory }.getOrDefault(false),
                    sizeBytes = runCatching { child.length() }.getOrDefault(0L),
                )
            }
        }

        override fun read(relativePath: String): ByteArray {
            val document = resolve(relativePath) ?: throw FileNotFoundException(relativePath)
            val stream = resolver.openInputStream(document.uri)
                ?: throw FileNotFoundException(relativePath)
            return stream.use { input -> input.readBytes() }
        }

        /** Walks named segments from the tree root; a segment can never traverse out of it. */
        private fun resolve(relativePath: String): DocumentFile? {
            if (relativePath.isEmpty()) return root
            var current = root
            for (segment in relativePath.split('/')) {
                if (segment.isEmpty()) continue
                current = runCatching { current.findFile(segment) }.getOrNull() ?: return null
            }
            return current
        }
    }

    private companion object {
        const val CONTENT_SCHEME: String = "content://"
        const val MIRROR_MARKER: String = ".agentx-mirror.ok"
    }
}
