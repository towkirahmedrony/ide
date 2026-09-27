package dev.forge.ide.workspace

import dev.forge.ide.core.architecture.LayerDescriptor
import dev.forge.ide.core.architecture.LayerStatus

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
