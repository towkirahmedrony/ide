package com.agentx.app.foundation

import com.agentx.app.agent.AGENT_LAYER
import com.agentx.app.agent.AgentModule
import com.agentx.app.codeintel.CODE_INTELLIGENCE_LAYER
import com.agentx.app.codeintel.CodeIntelligence
import com.agentx.app.codeintel.CodeIntelligenceLimits
import com.agentx.app.codeintel.CodeIntelligenceModule
import com.agentx.app.codeintel.DelegatingSyntaxParserProvider
import com.agentx.app.codeintel.SyntaxParserProvider
import com.agentx.app.context.CodeStructureContextProvider
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
import com.agentx.app.integrations.IntegrationsModule
import com.agentx.app.integrations.connection.ConnectionProviderRegistry
import com.agentx.app.integrations.connection.ConnectionSecretStore
import com.agentx.app.integrations.connection.ConnectionStore
import com.agentx.app.integrations.connection.InMemoryConnectionSecretStore
import com.agentx.app.integrations.connection.InMemoryConnectionStore
import com.agentx.app.integrations.setup.IntegrationSetupManager
import com.agentx.app.model.MODEL_LAYER
import com.agentx.app.model.ModelModule
import com.agentx.app.model.manager.ModelRuntimeModule
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.ModelPresetStore
import com.agentx.app.model.preset.ModelSecretStore
import com.agentx.app.model.runtime.RuntimeOutputBuffer
import com.agentx.app.skills.SKILLS_LAYER
import com.agentx.app.tools.BuiltinTools
import com.agentx.app.tools.DelegatingWorkspaceFileSystemResolver
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
    /** Structural understanding of source files, shared by the tools and the UI. */
    val codeIntelligence: CodeIntelligence,
    /**
     * The parser backend, bindable after boot. The app attaches the Android
     * tree-sitter provider here, exactly like the workspace resolver.
     */
    val codeIntelligenceParsers: SyntaxParserProvider,
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
        connectionStore: ConnectionStore = InMemoryConnectionStore(),
        connectionSecretStore: ConnectionSecretStore = InMemoryConnectionSecretStore(),
        connectionProviders: ConnectionProviderRegistry = ConnectionProviderRegistry.EMPTY,
        integrationSetup: IntegrationSetupManager? = null,
        runtimeOutput: RuntimeOutputBuffer = RuntimeOutputBuffer(),
        monitorModelConnections: Boolean = true,
        contextWorkspace: WorkspaceContextProvider = DelegatingWorkspaceContextProvider(),
        codeIntelligenceParsers: SyntaxParserProvider = DelegatingSyntaxParserProvider(),
        codeIntelligenceLimits: CodeIntelligenceLimits = CodeIntelligenceLimits.DEFAULT,
    ): FoundationState {
        val logger = ForgeLoggers.create(
            level = config.logLevel,
            baseFields = mapOf("app" to config.appName),
        )

        val services = ServiceContainer()
        services.register(ServiceKeys.LOGGER, logger)

        val layers = forgeLayers()

        // Code intelligence is created before the modules that consume it: the
        // Tool System publishes its tools and the Context Engine publishes file
        // structure, and neither of them builds a second parser.
        val toolWorkspaces = DelegatingWorkspaceFileSystemResolver()
        val codeIntelligence = CodeIntelligenceModule(
            parsers = codeIntelligenceParsers,
            limits = codeIntelligenceLimits,
        )

        val modules = ModuleRegistry(logger)
        modules.register(ConfigModule(config))
        modules.register(ArchitectureModule(layers))
        modules.register(
            ToolsModule(
                tools = BuiltinTools.codeIntelligence(toolWorkspaces, codeIntelligence.engine),
                workspaces = toolWorkspaces,
            ),
        )
        modules.register(codeIntelligence)
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
        // it from the container to build its run context. It receives the
        // structure of the files a task is about, bounded and redacted like every
        // other context item.
        modules.register(
            ContextModule(
                workspace = contextWorkspace,
                providers = listOf(
                    CodeStructureContextProvider(
                        codeIntelligence = codeIntelligence.engine,
                        workspace = contextWorkspace,
                    ),
                ),
            ),
        )
        // External service connections live beside the tool system; the agent
        // never receives credentials, only authorized capability handles.
        modules.register(
            IntegrationsModule(
                connectionStore = connectionStore,
                secretStore = connectionSecretStore,
                providers = connectionProviders,
                setup = integrationSetup,
            ),
        )
        modules.register(AgentModule())
        modules.initialize(services)

        val health = HealthMonitor()
            .register { configCheck(config) }
            .register { moduleCheck(modules, services) }
            .register { architectureCheck(layers) }
            .register { modelCheck(services) }
            .register { connectionsCheck(services) }
            .run()

        logger.info("Foundation ready", mapOf("layers" to layers.size, "status" to health.status))

        return FoundationState(
            layers = layers,
            health = health,
            config = config,
            services = services,
            contextWorkspace = contextWorkspace,
            codeIntelligence = codeIntelligence.engine,
            codeIntelligenceParsers = codeIntelligenceParsers,
        )
    }

    private fun forgeLayers(): List<LayerDescriptor> = listOf(
        CORE_LAYER,
        MODEL_LAYER,
        AGENT_LAYER,
        TOOLS_LAYER,
        WORKSPACE_LAYER,
        CODE_INTELLIGENCE_LAYER,
        CONTEXT_LAYER,
        GIT_LAYER,
        SKILLS_LAYER,
        INTEGRATIONS_LAYER,
        UI_LAYER,
    )
}
