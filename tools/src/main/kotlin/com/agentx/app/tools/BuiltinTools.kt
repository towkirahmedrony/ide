package com.agentx.app.tools

import com.agentx.app.codeintel.CodeIntelligence
import com.agentx.app.tools.codeintel.FindDefinitionTool
import com.agentx.app.tools.codeintel.FindReferencesTool
import com.agentx.app.tools.codeintel.GetFileOutlineTool
import com.agentx.app.tools.codeintel.GetFileSymbolsTool
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

    /**
     * Read-only structural tools. They go through the same registry, router and
     * permission policy as every other tool; none of them reads outside the open
     * workspace and none of them mutates a file.
     */
    fun codeIntelligence(
        workspaces: WorkspaceFileSystemResolver,
        intelligence: CodeIntelligence,
    ): List<Tool> = listOf(
        GetFileSymbolsTool(workspaces, intelligence),
        GetFileOutlineTool(workspaces, intelligence),
        FindDefinitionTool(workspaces, intelligence),
        FindReferencesTool(workspaces, intelligence),
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
