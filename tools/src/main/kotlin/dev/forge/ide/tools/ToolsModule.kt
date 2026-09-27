package dev.forge.ide.tools

import dev.forge.ide.core.foundation.ServiceKeys
import dev.forge.ide.core.module.ForgeModule
import dev.forge.ide.core.module.ModuleContext

/**
 * Wires the tool infrastructure into the platform. Feature tasks register their
 * own concrete tools; this module only publishes the registry, router, and
 * default permission policy, so it stays independent of any specific tool.
 */
class ToolsModule(
    tools: List<Tool> = emptyList(),
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
