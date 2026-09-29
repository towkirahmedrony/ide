package com.agentx.app.ui.ide.model

/**
 * Plain presentation models for the IDE shell. They intentionally contain no
 * Android, filesystem, or model-provider types so the UI can be driven by any
 * future Workspace Runtime implementation.
 */

/** A project/workspace the user can reopen from Home. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val rootPath: String,
    val branch: String? = null,
    val lastOpenedLabel: String = "just now",
    val fileCount: Int = 0,
)

enum class FileNodeKind { FILE, DIRECTORY }

/**
 * How far the workspace runtime has got with reading a directory.
 *
 * Folders are read on demand, so a folder that was never opened stays
 * [UNLOADED] without ever showing a spinner, and a folder that was read is
 * [LOADED] even when it turned out to be empty.
 */
enum class DirectoryLoadState { UNLOADED, LOADING, LOADED, ERROR }

/**
 * A node in the workspace file tree.
 *
 * Directories carry the state of their own read ([loadState], [errorMessage])
 * and their [children] only appear once that read succeeded.
 */
data class FileNode(
    val path: String,
    val name: String,
    val kind: FileNodeKind,
    val children: List<FileNode> = emptyList(),
    val loadState: DirectoryLoadState = DirectoryLoadState.UNLOADED,
    val errorMessage: String? = null,
) {
    val isDirectory: Boolean get() = kind == FileNodeKind.DIRECTORY

    /** A directory that was read successfully and has nothing in it. */
    val isEmptyDirectory: Boolean
        get() = isDirectory && loadState == DirectoryLoadState.LOADED && children.isEmpty()
}

/** A file loaded into the editor. */
data class OpenFile(
    val path: String,
    val name: String,
    val content: String,
)

enum class AgentActivityStatus {
    IDLE,
    SENDING,
    THINKING,
    USING_TOOL,
    TOOL_SUCCESS,
    TOOL_FAILURE,
    PERMISSION_REQUIRED,
    WAITING,
    COMPLETED,
    CONNECTION_ERROR,
    TIMEOUT,
    INVALID_RESPONSE,
    ERROR,
}

data class AgentActivity(
    val status: AgentActivityStatus,
    val label: String,
)

enum class ChatRole { USER, AGENT, SYSTEM, TOOL }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
    /** Set for [ChatRole.TOOL] entries so results can be matched to their call. */
    val toolName: String? = null,
)

// The line-based terminal models that used to live here are gone with the custom terminal:
// the Terminal tab renders the vendored Termux emulator, which owns its screen buffer.

/** A single changed file reported by the (future) Git layer. */
data class GitChange(
    val path: String,
    val status: String,
)

data class GitSnapshot(
    val available: Boolean,
    val branch: String? = null,
    val changes: List<GitChange> = emptyList(),
    val diff: String? = null,
)
