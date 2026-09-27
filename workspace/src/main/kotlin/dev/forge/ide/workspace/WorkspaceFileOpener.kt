package dev.forge.ide.workspace

import dev.forge.ide.core.ForgeResult
import dev.forge.ide.core.errorOrNull
import dev.forge.ide.core.valueOrNull

/**
 * Decides whether a workspace entry can be shown in the text editor.
 *
 * Only formats that are certainly not text are rejected up front; everything
 * else is read and sniffed by [looksBinary], so project-specific extensions
 * (`.kts`, `.pro`, `.env`, `.gitignore`, …) keep working without a whitelist to
 * maintain.
 */
object WorkspaceTextFiles {

    private val BINARY_EXTENSIONS = setOf(
        // Images
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "tif", "tiff", "svgz",
        // Documents / archives
        "pdf", "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "tar",
        // Compiled / packaged
        "jar", "apk", "aab", "aar", "dex", "class", "so", "dylib", "dll", "exe", "bin", "o", "a", "wasm",
        // Fonts
        "ttf", "otf", "woff", "woff2", "eot",
        // Audio / video
        "mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "mp4", "m4v", "mov", "avi", "mkv", "webm", "3gp",
        // Databases / binary config
        "db", "sqlite", "sqlite3", "realm", "keystore", "jks", "bks", "p12", "pfx", "der",
    )

    /** Characters inspected when sniffing freshly read content. */
    private const val SNIFF_CHARACTERS = 4096

    /** Share of non-text characters that makes content count as binary, in percent. */
    private const val BINARY_PERCENT_THRESHOLD = 10

    /** `false` only for formats that are known to be binary. */
    fun isLikelyText(path: String): Boolean = extension(path) !in BINARY_EXTENSIONS

    /**
     * Detects binary content that slipped through the extension check (a
     * mislabelled file, or a file without an extension). Text decoded as UTF-8
     * only contains a replacement character when the bytes were not text.
     */
    fun looksBinary(content: String): Boolean {
        if (content.isEmpty()) return false
        if (content.indexOf('\u0000') >= 0) return true

        val sample = content.take(SNIFF_CHARACTERS)
        val suspicious = sample.count { it == '\uFFFD' || it.isNonTextControl() }
        return suspicious * 100 / sample.length >= BINARY_PERCENT_THRESHOLD
    }

    private fun Char.isNonTextControl(): Boolean =
        code in 1..8 || code == 11 || code == 12 || code in 14..31

    private fun extension(path: String): String {
        val name = WorkspacePath.name(path)
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }
}

/**
 * Reads the file a user selected so the editor can show it.
 *
 * Only the requested file is read — never the folder it lives in — and content
 * is classified before it reaches the editor so binary/media files get a clear
 * message instead of a wall of replacement characters.
 */
class WorkspaceFileOpener(
    private val fileSystem: WorkspaceFileSystem,
    private val textFiles: WorkspaceTextFiles = WorkspaceTextFiles,
) {

    /** Outcome of opening one workspace entry. */
    sealed interface Opened {

        /** The file is text and its contents are available. */
        data class Text(val path: String, val name: String, val content: String) : Opened

        /** The file is not text and cannot be shown in the editor. */
        data class Unsupported(val path: String, val name: String, val error: WorkspaceError) : Opened

        /** The file exists but could not be read (permission, size, storage). */
        data class Failed(val path: String, val name: String, val error: WorkspaceError) : Opened
    }

    suspend fun open(path: String): Opened {
        val normalized = WorkspacePath.normalize(path)
        val rel = normalized.valueOrNull()
            ?: return Opened.Failed(
                path = path,
                name = WorkspacePath.name(path),
                error = normalized.errorOrNull() ?: invalidPath(path),
            )
        val name = WorkspacePath.name(rel)

        if (!textFiles.isLikelyText(rel)) return unsupported(rel, name)

        return when (val result = fileSystem.readFile(rel)) {
            is ForgeResult.Success ->
                if (textFiles.looksBinary(result.value)) {
                    unsupported(rel, name)
                } else {
                    Opened.Text(path = rel, name = name, content = result.value)
                }

            is ForgeResult.Failure -> Opened.Failed(path = rel, name = name, error = result.error)
        }
    }

    private fun unsupported(path: String, name: String): Opened.Unsupported = Opened.Unsupported(
        path = path,
        name = name,
        error = WorkspaceError(
            code = WorkspaceErrorCode.UNSUPPORTED_FILE_TYPE,
            message = "This file is not a text file.",
            path = path,
        ),
    )

    private fun invalidPath(path: String): WorkspaceError =
        WorkspaceError(WorkspaceErrorCode.INVALID_PATH, "The path is not valid.", path)
}
