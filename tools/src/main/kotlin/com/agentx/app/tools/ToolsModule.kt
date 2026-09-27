package com.agentx.app.tools

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext

/**
 * Wires the tool infrastructure into the platform. Filesystem tools are
 * registered against a bindable workspace resolver so the Android app can
 * attach the live Workspace Runtime after boot.
 */
class ToolsModule(
    private val tools: List<Tool> = emptyList(),
    private val policy: ToolPermissionPolicy = ToolPermissionPolicy.default(),
    private val workspaces: WorkspaceFileSystemResolver = DelegatingWorkspaceFileSystemResolver(),
) : ForgeModule {

    private val registry = DefaultToolRegistry()

    override val id: String = "tools"

    override fun initialize(context: ModuleContext) {
        val resolver = workspaces
        BuiltinTools.filesystem(resolver).forEach(registry::register)
        tools.forEach(registry::register)
        context.services.register(ServiceKeys.TOOL_REGISTRY, registry)
        context.services.register(ServiceKeys.TOOL_ROUTER, DefaultToolRouter(registry, policy))
        context.services.register(ServiceKeys.TOOL_PERMISSION_POLICY, policy)
        context.services.register(ServiceKeys.TOOL_WORKSPACE_RESOLVER, resolver)
    }
}
