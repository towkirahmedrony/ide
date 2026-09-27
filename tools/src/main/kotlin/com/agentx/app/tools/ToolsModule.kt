package com.agentx.app.tools

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext

/**
 * Wires the tool infrastructure into the platform. Feature tasks register their
 * own concrete tools; this module only publishes the registry, router, and
 * default permission policy, so it stays independent of any specific tool.
 */
class ToolsModule(
    private val tools: List<Tool> = emptyList(),
    private val policy: ToolPermissionPolicy = ToolPermissionPolicy.default(),
) : ForgeModule {

    private val registry = DefaultToolRegistry()

    override val id: String = "tools"

    override fun initialize(context: ModuleContext) {
        tools.forEach(registry::register)
        context.services.register(ServiceKeys.TOOL_REGISTRY, registry)
        context.services.register(ServiceKeys.TOOL_ROUTER, DefaultToolRouter(registry, policy))
        context.services.register(ServiceKeys.TOOL_PERMISSION_POLICY, policy)
    }
}
