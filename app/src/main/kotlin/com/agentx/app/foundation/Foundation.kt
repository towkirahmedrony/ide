package com.agentx.app.foundation

import com.agentx.app.agent.AGENT_LAYER
import com.agentx.app.agent.AgentModule
import com.agentx.app.agent.conversation.ConversationStore
import com.agentx.app.agent.conversation.InMemoryConversationStore
import com.agentx.app.agent.model.AgentRoleModelRegistry
import com.agentx.app.agent.model.AgentRoleModelStore
import com.agentx.app.agent.model.DefaultAgentRoleModelRepository
import com.agentx.app.agent.model.InMemoryAgentRoleModelStore
import com.agentx.app.agent.prompt.AgentPromptStore
import com.agentx.app.agent.prompt.DefaultAgentPromptRepository
import com.agentx.app.agent.prompt.InMemoryAgentPromptStore
import com.agentx.app.agent.prompt.PromptManager
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
import com.agentx.app.core.logging.ConsoleLogSink
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogSink
import com.agentx.app.core.timeout.AgentTimeouts
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
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.catalog.DefaultModelCatalogRegistry
import com.agentx.app.model.catalog.InMemoryModelCatalogStore
import com.agentx.app.model.catalog.ModelCatalogRegistry
import com.agentx.app.model.catalog.ModelCatalogStore
import com.agentx.app.model.catalog.RemoteModelCatalogFactory
import com.agentx.app.model.manager.DefaultModelDiscoverySource
import com.agentx.app.model.manager.ModelManager
import com.agentx.app.model.manager.ModelRuntimeModule
import com.agentx.app.model.ratelimit.CatalogRateLimitLimitSource
import com.agentx.app.model.ratelimit.DefaultRateLimitManager
import com.agentx.app.model.ratelimit.InMemoryRateLimitProfileStore
import com.agentx.app.model.ratelimit.RateLimitProfileStore
import com.agentx.app.model.preset.InMemoryModelPresetStore
import com.agentx.app.model.preset.InMemoryModelSecretStore
import com.agentx.app.model.preset.ModelPresetStore
import com.agentx.app.model.preset.ModelSecretStore
import com.agentx.app.model.runtime.RuntimeOutputBuffer
import com.agentx.app.skills.DefaultSkillManager
import com.agentx.app.skills.InMemorySkillStore
import com.agentx.app.skills.SKILLS_LAYER
import com.agentx.app.skills.SkillDiscoverySource
import com.agentx.app.skills.SkillManager
import com.agentx.app.skills.SkillStore
import com.agentx.app.git.DelegatingGitService
import com.agentx.app.tools.BuiltinTools
import com.agentx.app.tools.DelegatingWorkspaceFileSystemResolver
import com.agentx.app.tools.DelegatingWorkspaceHostPathResolver
import com.agentx.app.tools.TOOLS_LAYER
import com.agentx.app.tools.ToolsModule
import com.agentx.app.tools.web.DuckDuckGoWebSearchProvider
import com.agentx.app.tools.web.HttpGetClient
import com.agentx.app.tools.web.UrlConnectionHttpGetClient
import com.agentx.app.tools.web.WebSearchProvider
import com.agentx.app.ui.UI_LAYER
import com.agentx.app.workspace.WORKSPACE_LAYER
import com.agentx.app.workspace.WorkspaceModule
import com.agentx.app.workspace.process.JvmProcessRuntime
import com.agentx.app.workspace.process.RuntimeProcessExecutor

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
    /** Central agent system-prompt manager (defaults + user overrides). */
    val promptManager: PromptManager,
    /**
     * The single authoritative role → model configuration. Settings writes here
     * and the Agent Core's model resolver reads here, so the two never disagree.
     */
    val agentRoleModels: AgentRoleModelRegistry,
    /** Central skills registry and manager. */
    val skillManager: SkillManager,
    /** Per-operation execution budgets shared by every agent layer. */
    val timeouts: AgentTimeouts = AgentTimeouts.DEFAULT,
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
        agentPromptStore: AgentPromptStore = InMemoryAgentPromptStore(),
        /**
         * Where discovered provider models are kept between runs. The app passes a
         * `SharedPreferences`-backed store so a restart restores the catalog a
         * previous run discovered instead of falling back to a built-in list.
         */
        modelCatalogStore: ModelCatalogStore = InMemoryModelCatalogStore(),
        /** Persisted per-role model assignments; defaults when none are stored. */
        agentRoleModelStore: AgentRoleModelStore = InMemoryAgentRoleModelStore(),
        /**
         * Where configured rate-limit profiles are kept between runs. One shared
         * instance backs both the manager that enforces them and each connection
         * that publishes its provider's documented quota, so a limit configured
         * once is in force after a restart without being restated.
         */
        rateLimitProfileStore: RateLimitProfileStore = InMemoryRateLimitProfileStore(),
        conversationStore: ConversationStore = InMemoryConversationStore(),
        skillStore: SkillStore = InMemorySkillStore(),
        skillSources: List<SkillDiscoverySource> = emptyList(),
        runtimeOutput: RuntimeOutputBuffer = RuntimeOutputBuffer(),
        monitorModelConnections: Boolean = true,
        contextWorkspace: WorkspaceContextProvider = DelegatingWorkspaceContextProvider(),
        codeIntelligenceParsers: SyntaxParserProvider = DelegatingSyntaxParserProvider(),
        codeIntelligenceLimits: CodeIntelligenceLimits = CodeIntelligenceLimits.DEFAULT,
        /**
         * Per-operation execution budgets for the whole agent stack. One value
         * object, so the Agent Core, the Tool System and the Model Gateway can
         * never drift apart; see [AgentTimeouts].
         */
        timeouts: AgentTimeouts = AgentTimeouts.DEFAULT,
        /**
         * Where structured records are written. The app passes a sink that also
         * mirrors them into the Developer Log; tests and headless callers keep the
         * console sink.
         */
        logSink: LogSink = ConsoleLogSink,
    ): FoundationState {
        val logger = ForgeLoggers.create(
            level = config.logLevel,
            sink = logSink,
            baseFields = mapOf("app" to config.appName),
        )

        val services = ServiceContainer()
        services.register(ServiceKeys.LOGGER, logger)

        // Prompt and skill configuration are registered before the Agent Core so
        // it can resolve prompts and skills without owning their storage.
        val promptManager = PromptManager(DefaultAgentPromptRepository(agentPromptStore))
        val skillManager = DefaultSkillManager(sources = skillSources, store = skillStore)
        services.register(ServiceKeys.AGENT_PROMPTS, promptManager)
        services.register(ServiceKeys.SKILLS, skillManager)
        // The role → model configuration is created before the Agent Core so the
        // resolver can read it live instead of owning a private mapping.
        val roleModels = AgentRoleModelRegistry(
            DefaultAgentRoleModelRepository(agentRoleModelStore),
        )
        services.register(ServiceKeys.AGENT_ROLE_MODELS, roleModels)
        // Execution budgets are registered before the agent modules so every
        // layer resolves the same configuration instead of a private constant.
        services.register(ServiceKeys.AGENT_TIMEOUTS, timeouts)
        // Agent session conversations are the Agent Core's own persistent history;
        // registering the store here means the Agent module binds to it instead of
        // falling back to an in-memory one.
        services.register(ServiceKeys.AGENT_CONVERSATION_STORE, conversationStore)

        // Central rate limiting and usage tracking are registered before the
        // model module so the published gateway routes every remote request
        // through one admission point. Local (on-device) runtimes are exempt.
        //
        // The manager is given the configured profile store and the provider quota
        // catalog, which together are what make it more than reactive: quotas saved
        // by a previous run are in force from the first request, and a documented
        // provider ceiling is enforceable for a request that no registered profile
        // covered. Without both, the manager holds no limits at all and every
        // provider looks unlimited until a 429 says otherwise.
        val rateLimitManager = DefaultRateLimitManager(
            profileStore = rateLimitProfileStore,
            limitSource = CatalogRateLimitLimitSource(),
        )
        services.register(ServiceKeys.RATE_LIMIT_MANAGER, rateLimitManager)
        services.register(ServiceKeys.MODEL_USAGE, rateLimitManager.usage)
        // One shared capability registry: catalog/connect register identities here
        // and the gateway/resolver read the same instance. Hardcoded overlays stay
        // authoritative; discovery never invents tool calling.
        val capabilityRegistry: ModelCapabilityRegistry = InMemoryModelCapabilityRegistry()
        services.register(ServiceKeys.MODEL_CAPABILITY_REGISTRY, capabilityRegistry)
        // The dynamic model catalog is built lazily from the providers that can be
        // listed, so connecting Groq later simply gives it a fresh catalog. Listing
        // uses catalogConnections, not connections: a saved Gemini (or Groq) provider
        // that is not the active connection still exposes its live model list to
        // Settings, which is what keeps a built-in fallback list out of the picker.
        // The agent resolver keeps reading connections, so routing is unchanged.
        //
        // Discovery belongs to the provider: each connection is listed through the
        // same provider the runtime chats through, and the catalog layer owns only
        // normalization, registration and persistence. The store is the same
        // instance the registry restores from, so a restart keeps the catalog.
        val modelCatalogFactory = RemoteModelCatalogFactory(
            store = modelCatalogStore,
            capabilityRegistry = capabilityRegistry,
            discoverySource = DefaultModelDiscoverySource(logger = logger)::create,
        )
        val modelCatalog: ModelCatalogRegistry = DefaultModelCatalogRegistry(
            connections = {
                services.get<ModelManager>(ServiceKeys.MODEL_MANAGER)?.catalogConnections().orEmpty()
            },
            factory = modelCatalogFactory,
            store = modelCatalogStore,
            capabilityRegistry = capabilityRegistry,
        )
        services.register(ServiceKeys.MODEL_CATALOG, modelCatalog)

        val layers = forgeLayers()

        // Code intelligence is created before the modules that consume it: the
        // Tool System publishes its tools and the Context Engine publishes file
        // structure, and neither of them builds a second parser.
        val toolWorkspaces = DelegatingWorkspaceFileSystemResolver()
        // One-shot command execution backend, shared with the Workspace Runtime so
        // the command tool and the rest of the platform use the same executor.
        val processRuntime = JvmProcessRuntime()
        val processExecutor = RuntimeProcessExecutor(processRuntime, allowsArbitrary = false)
        // Bindable collaborators the app attaches after boot: the workspace host
        // path (for commands) and the live Git service (for the git tools).
        val toolHostPaths = DelegatingWorkspaceHostPathResolver()
        val gitService = DelegatingGitService()
        // Web research is real out of the box: a URL-connection transport backs
        // web_fetch, and a provider-agnostic search provider backs web_search.
        val webFetch: HttpGetClient = UrlConnectionHttpGetClient()
        val webSearch: WebSearchProvider = DuckDuckGoWebSearchProvider(webFetch)
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
                hostPaths = toolHostPaths,
                commandExecutor = processExecutor,
                git = gitService,
                webFetch = webFetch,
                webSearch = webSearch,
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
        modules.register(WorkspaceModule(runtime = processRuntime, executor = processExecutor))
        // The module reads the registered budgets from the container; the value
        // passed here is only the fallback for an unregistered container.
        modules.register(AgentModule(timeouts))
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
            promptManager = promptManager,
            skillManager = skillManager,
            agentRoleModels = roleModels,
            timeouts = timeouts,
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
