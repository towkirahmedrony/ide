package com.agentx.app.context

import com.agentx.app.core.ForgeResult
import com.agentx.app.core.valueOrNull
import com.agentx.app.tools.SecretRedactor
import com.agentx.app.workspace.WorkspaceDirectory
import com.agentx.app.workspace.WorkspaceErrorCode
import com.agentx.app.workspace.WorkspaceFileSystem
import com.agentx.app.workspace.WorkspacePath

/** Outcome of trying to turn one workspace path into a context item. */
sealed interface ContextLoad {

    /** The path was read and prepared for the model. */
    data class Loaded(val item: ContextItem) : ContextLoad

    /** The path was deliberately left out; [exclusion] says why. */
    data class Rejected(val exclusion: ContextExclusion) : ContextLoad
}

/**
 * Reads files and directory listings through the Workspace Runtime and turns
 * them into [ContextItem]s.
 *
 * Guarantees:
 * - protected paths are rejected before any read;
 * - every read goes through [WorkspaceFileSystem], so the workspace root can
 *   never be escaped;
 * - content is redacted with the Tool System's [SecretRedactor];
 * - content is truncated to the caller's budget and says so;
 * - raw content is cached, so one agent task reads a file once.
 */
class WorkspaceFileContextLoader(
    private val cache: WorkspaceFileCache,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun loadFile(
        fileSystem: WorkspaceFileSystem?,
        workspaceId: String?,
        path: String,
        reason: ContextReason,
        priority: ContextPriority,
        relevance: Double,
        maxChars: Int,
    ): ContextLoad {
        val normalized = WorkspacePath.normalize(path).valueOrNull()
            ?: return rejected(path, null, ContextExclusionReason.UNREADABLE, "Invalid workspace path")
        ProtectedPaths.reason(normalized)?.let { protection ->
            return rejected(normalized, normalized, ContextExclusionReason.PROTECTED_PATH, protection)
        }
        if (fileSystem == null) {
            return rejected(normalized, normalized, ContextExclusionReason.UNREADABLE, "No workspace is open")
        }

        val cacheKey = WorkspaceFileCache.key(workspaceId, normalized)
        val raw = cache.get(cacheKey) ?: when (val read = fileSystem.readFile(normalized)) {
            is ForgeResult.Success -> read.value.also { cache.put(cacheKey, it) }
            is ForgeResult.Failure ->
                return rejected(
                    normalized,
                    normalized,
                    exclusionReasonFor(read.error.code),
                    read.error.userMessage,
                )
        }
        if (raw.isBlank()) {
            return rejected(normalized, normalized, ContextExclusionReason.EMPTY, "File is empty")
        }

        val bounded = ContextTruncator.truncate(SecretRedactor.redactText(raw), maxChars)
        val now = clock()
        val item = ContextItem(
            id = "file:$normalized",
            source = ContextSource.FILE,
            content = bounded.text,
            priority = priority,
            relevance = relevance,
            path = normalized,
            title = WorkspacePath.name(normalized),
            metadata = ContextMetadata(
                reason = describe(reason),
                selectedBecause = reason,
                timestampMillis = now,
                originalChars = bounded.originalChars,
                attributes = mapOf(
                    "chars" to bounded.originalChars.toString(),
                    "truncated" to bounded.truncated.toString(),
                ),
            ),
            truncated = bounded.truncated,
            originalChars = bounded.originalChars,
            createdAtMillis = now,
        )
        return ContextLoad.Loaded(item)
    }

    suspend fun loadDirectory(
        fileSystem: WorkspaceFileSystem?,
        path: String,
        maxEntries: Int,
        relevance: Double = ContextRelevance.DIRECTORY,
    ): ContextLoad {
        val normalized = WorkspacePath.normalize(path).valueOrNull()
            ?: return rejected(path, null, ContextExclusionReason.UNREADABLE, "Invalid workspace path")
        if (fileSystem == null) {
            return rejected(normalized, normalized, ContextExclusionReason.UNREADABLE, "No workspace is open")
        }
        val entries = when (val listing = fileSystem.list(normalized)) {
            is ForgeResult.Success -> listing.value
            is ForgeResult.Failure ->
                return rejected(
                    normalized,
                    normalized,
                    exclusionReasonFor(listing.error.code),
                    listing.error.userMessage,
                )
        }

        val kept = entries.take(maxEntries)
        val truncated = kept.size < entries.size
        val body = if (kept.isEmpty()) {
            "(empty directory)"
        } else {
            buildString {
                kept.forEach { node ->
                    append(if (node is WorkspaceDirectory) "dir  " else "file ").append(node.path).append('\n')
                }
                if (truncated) append("…[showing ${kept.size} of ${entries.size} entries]")
            }.trimEnd()
        }
        val now = clock()
        val item = ContextItem(
            id = "directory:${normalized.ifEmpty { "." }}",
            source = ContextSource.DIRECTORY,
            content = body,
            priority = ContextPriority.LOW,
            relevance = relevance,
            path = normalized,
            title = normalized.ifEmpty { "/" },
            metadata = ContextMetadata(
                reason = "Directory listing",
                selectedBecause = ContextReason.DIRECTORY,
                timestampMillis = now,
                attributes = mapOf("entries" to entries.size.toString()),
            ),
            truncated = truncated,
            originalChars = body.length,
            createdAtMillis = now,
        )
        return ContextLoad.Loaded(item)
    }

    private fun rejected(
        path: String,
        idPath: String?,
        reason: ContextExclusionReason,
        detail: String?,
    ): ContextLoad.Rejected = ContextLoad.Rejected(
        ContextExclusion(
            id = "file:${idPath ?: path}",
            source = ContextSource.FILE,
            path = idPath ?: path,
            reason = reason,
            detail = detail,
        ),
    )

    private fun exclusionReasonFor(code: WorkspaceErrorCode): ContextExclusionReason = when (code) {
        WorkspaceErrorCode.NOT_FOUND,
        WorkspaceErrorCode.NOT_A_FILE,
        WorkspaceErrorCode.NOT_A_DIRECTORY,
        -> ContextExclusionReason.NOT_FOUND

        else -> ContextExclusionReason.UNREADABLE
    }

    private fun describe(reason: ContextReason): String = when (reason) {
        ContextReason.MENTIONED_FILE -> "File named in the current request"
        ContextReason.ATTACHMENT -> "File attached to this message"
        ContextReason.SELECTED_FILE -> "File open in the editor"
        ContextReason.RECENT_FILE -> "Recently used file"
        ContextReason.SEARCH_RESULT -> "File found by search"
        ContextReason.DIRECTORY -> "Directory listing"
        ContextReason.WORKSPACE_INFO -> "Workspace information"
        ContextReason.CONVERSATION -> "Conversation history"
        ContextReason.TOOL_RESULT -> "Tool result"
        ContextReason.AGENT_STATE -> "Agent state"
        ContextReason.CURRENT_REQUEST -> "Current user request"
        ContextReason.PROVIDER -> "Context provider"
        ContextReason.SKILL -> "Skill instructions"
        ContextReason.MANUAL -> "Added by the caller"
        ContextReason.PROJECT_DESIGN -> "Project design direction"
    }
}
