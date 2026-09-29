package com.agentx.app.termux

import java.io.File

/** One entry a [MirrorSource] reports. [name] must be a single path segment. */
data class MirrorEntry(
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long = 0L,
)

/** Where a mirror reads from. Implemented over SAF by the app, over `java.io.File` for tests. */
interface MirrorSource {
    fun list(relativePath: String): List<MirrorEntry>
    fun read(relativePath: String): ByteArray
}

/** Where a mirror writes to. Always a directory this app owns. */
interface MirrorSink {
    fun createDirectory(relativePath: String)
    fun write(relativePath: String, bytes: ByteArray)
}

sealed interface MirrorOutcome {
    data class Completed(val files: Int, val directories: Int, val bytes: Long) : MirrorOutcome

    /** The user or the screen went away mid-copy. The mirror is left partial but consistent. */
    data class Cancelled(val files: Int, val directories: Int, val bytes: Long, val at: String) : MirrorOutcome

    data class Failed(val reason: String) : MirrorOutcome

    /** What the UI shows after either outcome. */
    val summary: String
        get() = when (this) {
            is Completed -> "$files file(s) mirrored, $bytes byte(s). Read-only copy: edits made " +
                "in the terminal are not written back to the original folder."
            is Cancelled -> "Mirror cancelled after $files file(s); the copy is incomplete and " +
                "not written back."
            is Failed -> "Mirroring failed: $reason The shell is running in home instead."
        }
}

/**
 * Copies a workspace into an app-owned directory.
 *
 * Deliberately not a coroutine: cancellation is a plain predicate, so the whole engine is a
 * deterministic, synchronous function that the caller runs on `Dispatchers.IO` and cancels by
 * flipping the predicate. That makes every rule below — including the hostile-name rules and the
 * cancellation checkpoints — testable on the JVM without an Android runtime.
 */
object TermuxWorkspaceMirror {

    /** Copied trees are bounded so a mistyped tree cannot fill the data partition. */
    const val MAX_FILES: Int = 20_000
    const val MAX_TOTAL_BYTES: Long = 512L * 1024 * 1024
    const val MAX_DEPTH: Int = 32

    /**
     * A single path segment that cannot traverse, hide or collide with a directory.
     *
     * Returns null when [name] is empty, is `.` or `..`, contains a separator or a NUL, or tries
     * to be an absolute path.
     */
    fun safeRelativeName(name: String): String? {
        if (name.isEmpty() || name.length > 255) return null
        if (name == "." || name == "..") return null
        if (name.contains('/') || name.contains('\\') || name.contains('\u0000')) return null
        return name
    }

    fun mirror(
        source: MirrorSource,
        sink: MirrorSink,
        isCancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): MirrorOutcome {
        var files = 0
        var directories = 0
        var bytes = 0L

        fun copy(relativePath: String, depth: Int): MirrorOutcome? {
            if (isCancelled()) return MirrorOutcome.Cancelled(files, directories, bytes, relativePath)
            if (depth > MAX_DEPTH) {
                return MirrorOutcome.Failed("the folder tree is deeper than $MAX_DEPTH levels at '$relativePath'.")
            }

            val entries = try {
                source.list(relativePath)
            } catch (failure: Exception) {
                return MirrorOutcome.Failed("could not read '$relativePath' (${failure.javaClass.simpleName}).")
            }

            for (entry in entries) {
                if (isCancelled()) return MirrorOutcome.Cancelled(files, directories, bytes, relativePath)

                val name = safeRelativeName(entry.name)
                    ?: return MirrorOutcome.Failed(
                        "the source reported an unusable entry name '${entry.name}' in '$relativePath'.",
                    )
                val child = if (relativePath.isEmpty()) name else "$relativePath/$name"

                if (entry.directory) {
                    try {
                        sink.createDirectory(child)
                    } catch (failure: Exception) {
                        return MirrorOutcome.Failed("could not create '$child' (${failure.javaClass.simpleName}).")
                    }
                    directories++
                    copy(child, depth + 1)?.let { return it }
                    continue
                }

                if (files >= MAX_FILES) {
                    return MirrorOutcome.Failed("the folder holds more than $MAX_FILES files.")
                }
                if (bytes + entry.sizeBytes > MAX_TOTAL_BYTES) {
                    return MirrorOutcome.Failed("the folder is larger than ${MAX_TOTAL_BYTES / (1024 * 1024)} MiB.")
                }

                val content = try {
                    source.read(child)
                } catch (failure: Exception) {
                    return MirrorOutcome.Failed("could not read '$child' (${failure.javaClass.simpleName}).")
                }
                // Re-check after the read: a read of a large file is where a cancel usually lands.
                if (isCancelled()) return MirrorOutcome.Cancelled(files, directories, bytes, child)

                try {
                    sink.write(child, content)
                } catch (failure: Exception) {
                    return MirrorOutcome.Failed("could not write '$child' (${failure.javaClass.simpleName}).")
                }
                files++
                bytes += content.size.toLong()
                onProgress(child)
            }
            return null
        }

        return copy("", 0) ?: MirrorOutcome.Completed(files, directories, bytes)
    }
}

/** A [MirrorSource] over an ordinary directory. Used for workspaces that already have a path. */
class FileMirrorSource(private val root: File) : MirrorSource {
    override fun list(relativePath: String): List<MirrorEntry> {
        val directory = if (relativePath.isEmpty()) root else File(root, relativePath)
        val children = directory.listFiles() ?: return emptyList()
        return children.map { child ->
            MirrorEntry(name = child.name, directory = child.isDirectory, sizeBytes = child.length())
        }
    }

    override fun read(relativePath: String): ByteArray = File(root, relativePath).readBytes()
}

/** A [MirrorSink] over an app-owned directory. */
class FileMirrorSink(private val root: File) : MirrorSink {
    override fun createDirectory(relativePath: String) {
        val directory = File(root, relativePath)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("could not create $relativePath")
        }
    }

    override fun write(relativePath: String, bytes: ByteArray) {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }
}
