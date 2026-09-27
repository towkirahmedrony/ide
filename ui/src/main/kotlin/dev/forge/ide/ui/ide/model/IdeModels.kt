package dev.forge.ide.ui.ide.model

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

/** A node in the workspace file tree. */
data class FileNode(
    val path: String,
    val name: String,
    val kind: FileNodeKind,
    val children: List<FileNode> = emptyList(),
) {
    val isDirectory: Boolean get() = kind == FileNodeKind.DIRECTORY
}

/** A file loaded into the editor. */
data class OpenFile(
    val path: String,
    val name: String,
    val content: String,
)

enum class AgentActivityStatus { IDLE, THINKING, USING_TOOL, WAITING, COMPLETED, ERROR }

data class AgentActivity(
    val status: AgentActivityStatus,
    val label: String,
)

enum class ChatRole { USER, AGENT, SYSTEM }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
)

enum class TerminalLineKind { INPUT, OUTPUT, ERROR, SYSTEM }

data class TerminalLine(
    val id: String,
    val text: String,
    val kind: TerminalLineKind,
)

data class TerminalResult(
    val lines: List<TerminalLine>,
    val exitCode: Int = 0,
)

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
