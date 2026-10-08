package com.agentx.app.context

import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext

/**
 * Wires the Context Engine into the platform.
 *
 * The workspace provider is a [DelegatingWorkspaceContextProvider] by default,
 * so the engine can be registered at boot and pointed at the live Workspace
 * Runtime as soon as the app has one, exactly like the Tool System's workspace
 * resolver. Until then the engine simply returns no workspace context.
 */
class ContextModule(
    private val workspace: WorkspaceContextProvider = DelegatingWorkspaceContextProvider(),
    private val providers: List<ContextProvider> = emptyList(),
    private val budget: ContextBudget = ContextBudget.DEFAULT,
) : ForgeModule {

    override val id: String = "context"

    private val engine = DefaultContextEngine(
        workspace = workspace,
        providers = providers,
        budget = budget,
    )

    /**
     * Reads the open project's optional `DESIGN.md` for the roles that build or
     * judge UI. It is built here because reading the project root is a context-layer
     * concern: the Agent Core only ever sees the resolved design context.
     */
    private val designContext = ProjectDesignContextResolver(
        workspace = workspace,
        engine = engine,
    )

    /**
     * Detects the open project's target platform and selects the matching
     * AgentX-authored conventions. Built here for the same reason as the design
     * context: inspecting the project is a context-layer concern, and the Agent Core
     * only ever sees the selected profile.
     */
    private val platformProfile = ProjectPlatformProfileResolver(
        workspace = workspace,
        engine = engine,
    )

    override fun initialize(context: ModuleContext) {
        context.services.register(ServiceKeys.CONTEXT_ENGINE, engine)
        context.services.register(ServiceKeys.CONTEXT_DESIGN, designContext)
        context.services.register(ServiceKeys.CONTEXT_PLATFORM, platformProfile)
    }
}
