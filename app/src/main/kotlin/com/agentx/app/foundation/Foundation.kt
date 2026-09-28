package com.agentx.app.foundation

import com.agentx.app.agent.AGENT_LAYER
import com.agentx.app.agent.AgentModule
import com.agentx.app.context.CONTEXT_LAYER
import com.agentx.app.context.ContextModule
import com.agentx.app.context.DelegatingWorkspaceContextProvider
import com.agentx.app.context.WorkspaceContextProvider
import com.agentx.app.core.architecture.ArchitectureModule
import com.agentx.app.core.architecture.CORE_LAYER
import com.agentx.app.core.architecture.LayerDescriptor
import com.agentx.app.core.config.ConfigModule
import com.agentx.app.core.config.ForgeConfig
import com.agentx.app.core.di.ServiceContainer
import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.health.HealthMonitor
import com.agentx.app.core.health.HealthReport
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.module.ModuleRegistry
import com.agentx.app.git.GIT_LAYER
import com.agentx.app.integrations.INTEGRATIONS_LAYER
import com.agentx.app.model.MODEL_LAYER
import com.agentx.app.model.ModelModule
import com.agentx.app.model.manager.ModelRuntimeModule
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.ModelPresetStore
import com.agentx.app.model.preset.ModelSecretStore
import com.agentx.app.model.runtime.RuntimeOutputBuffer
import com.agentx.app.skills.SKILLS_LAYER
import com.agentx.app.tools.TOOLS_LAYER
import com.agentx.app.tools.ToolsModule
import com.agentx.app.ui.UI_LAYER
import com.agentx.app.workspace.WORKSPACE_LAYER

/** Everything the UI needs to render the running foundation. */
data class FoundationState(
    val layers: List<LayerDescriptor>,
    val health: HealthReport,
    val config: ForgeConfig,
    val services: ServiceContainer,
    /**
     * The Context Engine's workspace port. It is created at boot and pointed at
     * the live Workspace Runtime by the app, exactly like the Tool System's
     * workspace resolver.
     */
    val contextWorkspace: WorkspaceContextProvider,
)

/**
 * Composition root. Wires the core modules and layer descriptors together and
 * runs the startup health check. Feature modules will be registered here as
 * they are implemented.
 */
object Foundation {

    fun boot(
        config: ForgeConfig = ForgeConfig(),
        presetStore: ModelPresetStore = InMemoryModelPresetStore(),
        secretStore: ModelSecretStore = InMemoryModelSecretStore(),
        runtimeOutput: RuntimeOutputBuffer = RuntimeOutputBuffer(),
        monitorModelConnections: Boolean = true,
        contextWorkspace: WorkspaceContextProvider = DelegatingWorkspaceContextProvider(),
    ): FoundationState {
        val logger = ForgeLoggers.create(
            level = config.logLevel,
            baseFields = mapOf("app" to config.appName),
        )

        val services = ServiceContainer()
        services.register(ServiceKeys.LOGGER, logger)

        val layers = forgeLayers()

        val modules = ModuleRegistry(logger)
        modules.register(ConfigModule(config))
        modules.register(ArchitectureModule(layers))
        modules.register(ToolsModule())
        modules.register(ModelModule())
        // Model presets, runners and the active connection live beside the gateway.
        modules.register(
            ModelRuntimeModule(
                presetStore = presetStore,
                secretStore = secretStore,
                runtimeOutput = runtimeOutput,
                monitorEnabled = monitorModelConnections,
            ),
        )
        // The Context Engine is registered before the Agent Core, which reads
        // it from the container to build its run context.
        modules.register(ContextModule(workspace = contextWorkspace))
        modules.register(AgentModule())
        modules.initialize(services)

        val health = HealthMonitor()
            .register { configCheck(config) }
            .register { moduleCheck(modules, services) }
            .register { architectureCheck(layers) }
            .register { modelCheck(services) }
            .run()

        logger.info("Foundation ready", mapOf("layers" to layers.size, "status" to health.status))

        return FoundationState(
            layers = layers,
            health = health,
            config = config,
            services = services,
            contextWorkspace = contextWorkspace,
        )
    }

    private fun forgeLayers(): List<LayerDescriptor> = listOf(
        CORE_LAYER,
        MODEL_LAYER,
        AGENT_LAYER,
        TOOLS_LAYER,
        WORKSPACE_LAYER,
        CONTEXT_LAYER,
        GIT_LAYER,
        SKILLS_LAYER,
        INTEGRATIONS_LAYER,
        UI_LAYER,
    )
}
