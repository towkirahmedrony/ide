package com.agentx.app.git

import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.architecture.LayerStatus

enum class GitChangeType {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    UNTRACKED,
}

data class GitFileChange(
    val path: String,
    val type: GitChangeType,
)

data class GitStatus(
    val branch: String? = null,
    val changes: List<GitFileChange> = emptyList(),
)

/** Git port for a workspace. Implemented in a later task. */
interface GitService {
    suspend fun status(workspaceId: String): GitStatus

    suspend fun diff(workspaceId: String, paths: List<String> = emptyList()): String
}

val GIT_LAYER = LayerDescriptor(
    id = "git",
    title = "Git",
    summary = "Reads repository status and diffs so agents can reason about changes.",
    status = LayerStatus.CONTRACT_ONLY,
)
