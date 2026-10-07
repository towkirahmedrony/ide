package com.agentx.app.context

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.failure
import com.agentx.app.core.success
import com.agentx.app.core.valueOrNull
import com.agentx.app.workspace.WorkspaceError
import com.agentx.app.workspace.WorkspaceErrorCode
import com.agentx.app.workspace.WorkspaceFile
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspacePath
import com.agentx.app.workspace.WorkspaceResult
import com.agentx.app.workspace.WorkspaceTextFiles

/** A file the user picked outside the workspace, already read into memory. */
data class ExternalContent(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val text: String,
)

/** Why reading a file the user picked outside the workspace failed. */
enum class ExternalReadFailure {
    /** The URI could not be opened or was revoked. */
    UNREADABLE,

    /** The provider refused access. */
    PERMISSION_DENIED,

    /** The file is bigger than AgentX will read into memory. */
    TOO_LARGE,

    /** The provider reported no content at all. */
    EMPTY,
}

/**
 * Reads a file the user picked outside the workspace.
 *
 * This is the only platform-shaped part of attaching a file: everything else works on the
 * workspace. The Android layer implements it over `ContentResolver`, which is why this module —
 * and the materialiser that uses it — stays free of Android APIs and testable without a device.
 */
fun interface ExternalContentReader {
    suspend fun read(uri: String, maxBytes: Long): ForgeResult<ExternalContent, ExternalReadFailure>
}

/**
 * Turns a file the user picked outside the workspace into an attachment inside it.
 *
 * Attaching a file must give the agent the same thing as naming a file it already has: a
 * workspace-relative path the context engine can load and the file tools can read. So a file from
 * elsewhere is copied once into the workspace's own attachment directory and then treated exactly
 * like any other file — there is no attachment store, no second reader, and no parallel notion of
 * "attached content".
 *
 * Three rules decide what may enter this way:
 *
 * - **Only text.** [WorkspaceTextFiles] decides, on the extension and then on the bytes themselves.
 *   A PDF, a spreadsheet or an image is refused with a message naming the format. Binary bytes are
 *   never handed to a UTF-8 reader that would turn them into replacement characters the model would
 *   then be told are the file's content.
 * - **Only into the workspace.** The destination is a [WorkspacePath], so it is workspace-relative
 *   by construction, and the attachment is re-checked against [ProtectedPaths] *after* it lands.
 * - **Nothing protected.** An attachment may neither land on a protected path nor be readable from
 *   one, so attaching cannot be used to make `.ssh` or a private key readable by the agent.
 *
 * The attachment directory is hidden (`.agentx/attachments`) so it does not clutter the file tree,
 * and it is excluded from workspace search — an attachment is context the user handed the agent,
 * not source code. It is *not* excluded from the workspace's Git repository, so a user whose
 * project is a repository should ignore it; AgentX does not edit their `.gitignore` for them.
 */
class AttachmentMaterializer(
    private val reader: ExternalContentReader,
    private val textFiles: WorkspaceTextFiles = WorkspaceTextFiles,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    /**
     * Copies the file at [uri] into [fileSystem]'s attachment directory.
     *
     * [displayName] is only a hint: the destination name is derived from it through
     * [WorkspacePath.child], so a name carrying a separator, a `..` or a control character cannot
     * choose where the file lands — it is rejected and a safe name is used instead.
     */
    suspend fun materialize(
        uri: String,
        displayName: String,
        fileSystem: WorkspaceFileSystem,
    ): ForgeResult<AgentAttachment, WorkspaceError> {
        val directory = WorkspacePath.normalize(ATTACHMENT_DIRECTORY).valueOrNull()
            ?: return invalid("AgentX could not determine where to put the attachment.")

        val content = when (val read = reader.read(uri, maxBytes)) {
            is ForgeResult.Success -> read.value
            is ForgeResult.Failure -> return readFailure(read.error, displayName)
        }

        val name = safeName(displayName, content.displayName)
        val kind = kindFor(name)

        if (!textFiles.isLikelyText(name)) {
            return unsupported(kind, name)
        }
        if (textFiles.looksBinary(content.text)) {
            return unsupported(kind, name)
        }

        val destination = uniqueDestination(fileSystem, directory, name)
            ?: return invalid("AgentX could not find a free name for \"$name\" in the attachment folder.")

        ProtectedPaths.reason(destination)?.let { protection ->
            return failure(
                WorkspaceError(
                    WorkspaceErrorCode.PERMISSION_DENIED,
                    "AgentX will not attach \"$name\" because it matches a protected path ($protection).",
                    destination,
                ),
            )
        }

        ensureDirectory(fileSystem, directory)
        when (val created = fileSystem.createFile(destination)) {
            is ForgeResult.Failure -> return created
            is ForgeResult.Success -> Unit
        }
        when (val written = fileSystem.writeFile(destination, content.text)) {
            is ForgeResult.Failure -> return written
            is ForgeResult.Success -> Unit
        }

        // Re-checked after the copy: the destination is the path the agent will actually be given,
        // so it is the one that has to satisfy the protection rules.
        ProtectedPaths.reason(destination)?.let { protection ->
            runCatching { fileSystem.delete(destination) }
            return failure(
                WorkspaceError(
                    WorkspaceErrorCode.PERMISSION_DENIED,
                    "AgentX removed the attachment because it landed on a protected path ($protection).",
                    destination,
                ),
            )
        }

        return success(attachmentFor(destination, content.mimeType, content.sizeBytes, kind))
    }

    /**
     * An attachment for a file that is *already* in the workspace.
     *
     * The file is not copied and not moved: the path it already has is the path the agent is given.
     * Only its usability is checked, so attaching something the agent could never read fails here
     * rather than silently contributing nothing to the turn.
     */
    suspend fun attachExisting(
        path: String,
        fileSystem: WorkspaceFileSystem,
        mimeType: String = AgentAttachment.UNKNOWN_MIME_TYPE,
    ): ForgeResult<AgentAttachment, WorkspaceError> {
        val relative = when (val normalized = WorkspacePath.normalize(path)) {
            is ForgeResult.Success -> normalized.value
            is ForgeResult.Failure -> return normalized
        }
        if (relative.isEmpty()) return invalid("That is the workspace root, not a file.")

        ProtectedPaths.reason(relative)?.let { protection ->
            return failure(
                WorkspaceError(
                    WorkspaceErrorCode.PERMISSION_DENIED,
                    "AgentX will not attach \"${WorkspacePath.name(relative)}\" because it matches a " +
                        "protected path ($protection).",
                    relative,
                ),
            )
        }

        val node = when (val result = fileSystem.metadata(relative)) {
            is ForgeResult.Success -> result.value
            is ForgeResult.Failure -> return result
        }
        if (node !is WorkspaceFile) {
            return failure(
                WorkspaceError(
                    WorkspaceErrorCode.NOT_A_FILE,
                    "\"${WorkspacePath.name(relative)}\" is a folder.",
                    relative,
                ),
            )
        }

        val name = WorkspacePath.name(relative)
        val kind = kindFor(name)
        if (!textFiles.isLikelyText(name)) return unsupported(kind, name)

        val size = node.sizeBytes ?: 0L
        if (size > maxBytes) return tooLarge(name, size)

        return success(attachmentFor(relative, mimeType, size, kind))
    }

    /** The attachment directory, so callers can show or exclude it without re-deriving the name. */
    companion object {
        const val ATTACHMENT_DIRECTORY: String = ".agentx/attachments"

        /** Matches the workspace's own text read limit: nothing readable gets bigger than this. */
        const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024

        private val DOCUMENT_EXTENSIONS = setOf(
            "md", "markdown", "txt", "text", "rst", "adoc",
            "pdf", "doc", "docx", "odt", "rtf",
            "xls", "xlsx", "ods", "csv", "tsv",
            "ppt", "pptx", "odp",
        )

        /**
         * Exactly the image formats the workspace already treats as binary, so "this is an image"
         * and "this cannot be read as text" can never disagree. A text-based image format such as
         * SVG is deliberately absent: it *is* text, and the agent can read it.
         */
        private val IMAGE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "tif", "tiff",
        )

        private const val MAX_NAME_LENGTH = 120
        private const val MAX_NAME_ATTEMPTS = 50

        /** Classified from the file name, which is what the text/binary decision is made on too. */
        fun kindFor(fileName: String): AgentAttachmentKind {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return when {
                extension in IMAGE_EXTENSIONS -> AgentAttachmentKind.IMAGE
                extension in DOCUMENT_EXTENSIONS -> AgentAttachmentKind.DOCUMENT
                else -> AgentAttachmentKind.FILE
            }
        }
    }

    private suspend fun ensureDirectory(fileSystem: WorkspaceFileSystem, directory: String) {
        var current = ""
        directory.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            current = if (current.isEmpty()) segment else "$current/$segment"
            if (!fileSystem.exists(current)) fileSystem.createDirectory(current)
        }
    }

    /** Picks `name`, then `name-1.ext`, `name-2.ext` … so an existing file is never clobbered. */
    private suspend fun uniqueDestination(
        fileSystem: WorkspaceFileSystem,
        directory: String,
        name: String,
    ): String? {
        val base = name.substringBeforeLast('.', name)
        val suffix = name.substringAfterLast('.', "")
        repeat(MAX_NAME_ATTEMPTS) { attempt ->
            val candidate = if (attempt == 0) name else "$base-$attempt.${suffix.ifEmpty { "txt" }}"
            val path = WorkspacePath.child(directory, candidate).valueOrNull() ?: return@repeat
            if (!fileSystem.exists(path)) return path
        }
        return null
    }

    /**
     * A name that is one safe segment. A hint that cannot be made safe is replaced rather than
     * repaired, because a repaired name is still a name the user's provider chose.
     */
    private fun safeName(vararg hints: String): String {
        hints.forEach { hint ->
            val trimmed = hint.trim().takeLast(MAX_NAME_LENGTH)
            if (trimmed.isEmpty()) return@forEach
            if (WorkspacePath.child(ATTACHMENT_DIRECTORY, trimmed) is ForgeResult.Success) return trimmed
        }
        return "attachment"
    }

    private fun attachmentFor(
        path: String,
        mimeType: String,
        sizeBytes: Long,
        kind: AgentAttachmentKind,
    ): AgentAttachment = AgentAttachment(
        id = AgentAttachment.idFor(path),
        displayName = WorkspacePath.name(path),
        path = path,
        mimeType = mimeType.ifBlank { AgentAttachment.UNKNOWN_MIME_TYPE },
        sizeBytes = sizeBytes,
        kind = kind,
    )

    private fun unsupported(kind: AgentAttachmentKind, name: String): WorkspaceResult<Nothing> {
        val what = when (kind) {
            AgentAttachmentKind.IMAGE -> "an image"
            AgentAttachmentKind.DOCUMENT -> "a document in this format"
            AgentAttachmentKind.FILE -> "a binary file"
        }
        return failure(
            WorkspaceError(
                WorkspaceErrorCode.UNSUPPORTED_FILE_TYPE,
                "AgentX cannot read \"$name\" as text — it is $what, and only text files can be " +
                    "attached right now. Save it as text (plain text, Markdown, CSV, JSON or source) " +
                    "and attach that instead.",
            ),
        )
    }

    private fun tooLarge(name: String, size: Long): WorkspaceResult<Nothing> = failure(
        WorkspaceError(
            WorkspaceErrorCode.FILE_TOO_LARGE,
            "\"$name\" is ${size / 1024} KB, which is more than the ${maxBytes / 1024} KB AgentX " +
                "will attach.",
        ),
    )

    private fun readFailure(failure: ExternalReadFailure, name: String): WorkspaceResult<Nothing> {
        val (code, message) = when (failure) {
            ExternalReadFailure.UNREADABLE ->
                WorkspaceErrorCode.IO_FAILED to "AgentX could not read \"$name\"."

            ExternalReadFailure.PERMISSION_DENIED ->
                WorkspaceErrorCode.PERMISSION_DENIED to
                    "AgentX was not allowed to read \"$name\". Pick the file again and allow access."

            ExternalReadFailure.TOO_LARGE ->
                WorkspaceErrorCode.FILE_TOO_LARGE to
                    "\"$name\" is larger than the ${maxBytes / 1024} KB AgentX will attach."

            ExternalReadFailure.EMPTY ->
                WorkspaceErrorCode.IO_FAILED to "\"$name\" is empty."
        }
        return failure(WorkspaceError(code, message))
    }

    private fun invalid(message: String): WorkspaceResult<Nothing> =
        failure(WorkspaceError(WorkspaceErrorCode.INVALID_PATH, message))
}

