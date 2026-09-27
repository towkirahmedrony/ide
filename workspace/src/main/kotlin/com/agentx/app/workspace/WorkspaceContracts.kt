package com.agentx.app.workspace

import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.architecture.LayerStatus

/**
 * Architectural descriptor for the workspace layer. The runtime itself lives in
 * [WorkspaceManager], [WorkspaceFileSystem] and their implementations.
 */
val WORKSPACE_LAYER = LayerDescriptor(
    id = "workspace",
    title = "Workspace Runtime",
    summary = "Opens user-selected workspaces with a scoped filesystem; process execution remains a safe, disabled stub.",
    status = LayerStatus.ACTIVE,
)
