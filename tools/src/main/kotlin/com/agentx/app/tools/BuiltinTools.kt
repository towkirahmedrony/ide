package com.agentx.app.tools

import com.agentx.app.tools.filesystem.ListDirectoryTool
import com.agentx.app.tools.filesystem.ReadFileTool
import com.agentx.app.tools.filesystem.SearchFilesTool
import com.agentx.app.tools.filesystem.WriteFileTool

/** Factory for the first production tools. Dangerous categories are not included. */
object BuiltinTools {
    fun filesystem(workspaces: WorkspaceFileSystemResolver): List<Tool> = listOf(
        ListDirectoryTool(workspaces),
        ReadFileTool(workspaces),
        WriteFileTool(workspaces),
        SearchFilesTool(workspaces),
    )
}

/**
 * Workspace resolver that can be bound after the Workspace Runtime is created.
 * Tools fail closed with WORKSPACE_UNAVAILABLE until [bind] is called.
 */
class DelegatingWorkspaceFileSystemResolver(
    initial: WorkspaceFileSystemResolver? = null,
) : WorkspaceFileSystemResolver {

    @Volatile
    private var delegate: WorkspaceFileSystemResolver? = initial

    fun bind(resolver: WorkspaceFileSystemResolver) {
        delegate = resolver
    }

    override fun resolve(context: ToolExecutionContext) = delegate?.resolve(context)
}
