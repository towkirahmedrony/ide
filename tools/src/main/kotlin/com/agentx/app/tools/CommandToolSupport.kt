package com.agentx.app.tools

/**
 * Resolves the real host directory an agent-issued command runs in.
 *
 * A shell cannot run inside a workspace-relative abstraction: `sh -c` needs an
 * actual directory. A workspace backed by a real path (an AgentX-managed clone,
 * or a reachable folder) exposes one; a SAF tree or an unreachable folder does
 * not, and then [resolve] returns null so the command tool fails closed with
 * `WORKSPACE_UNAVAILABLE` instead of running in the app's own working directory.
 *
 * This mirrors [WorkspaceFileSystemResolver]: the app binds the live Workspace
 * Runtime / embedded runtime after boot, and the tool never touches Android.
 */
fun interface WorkspaceHostPathResolver {
    fun resolve(context: ToolExecutionContext): String?
}

/**
 * Bindable resolver, the same idea as [DelegatingWorkspaceFileSystemResolver].
 * Until [bind] is called it resolves nothing, so a command tool fails closed.
 */
class DelegatingWorkspaceHostPathResolver(
    @Volatile private var delegate: WorkspaceHostPathResolver = WorkspaceHostPathResolver { null },
) : WorkspaceHostPathResolver {

    fun bind(resolver: WorkspaceHostPathResolver) {
        delegate = resolver
    }

    override fun resolve(context: ToolExecutionContext): String? = delegate.resolve(context)
}
