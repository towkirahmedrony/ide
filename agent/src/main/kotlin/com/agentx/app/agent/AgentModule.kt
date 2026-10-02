package com.agentx.app.agent

import com.agentx.app.agent.conversation.ConversationHistory
import com.agentx.app.agent.conversation.ConversationStore
import com.agentx.app.agent.conversation.InMemoryConversationStore
import com.agentx.app.agent.main.MainAgent
import com.agentx.app.agent.model.AgentModelPreferences
import com.agentx.app.agent.model.AgentModelResolver
import com.agentx.app.agent.model.AgentRoleModelRegistry
import com.agentx.app.agent.model.ModelFallback
import com.agentx.app.agent.model.ModelFallbackPolicy
import com.agentx.app.agent.orchestrator.AgentOrchestrator
import com.agentx.app.agent.orchestrator.AgentSessionStore
import com.agentx.app.agent.orchestrator.DefaultAgentOrchestrator
import com.agentx.app.agent.orchestrator.InMemoryAgentSessionStore
import com.agentx.app.agent.prompt.PromptManager
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.specialized.SpecializedAgentFactory
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.context.ContextEngine
import com.agentx.app.context.DefaultContextEngine
import com.agentx.app.context.RunContextFactory
import com.agentx.app.context.SkillContextProvider
import com.agentx.app.context.SkillContextResolver
import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.timeout.AgentTimeouts
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelGateway
import com.agentx.app.model.capability.InMemoryModelCapabilityRegistry
import com.agentx.app.model.capability.ModelCapabilityRegistry
import com.agentx.app.model.manager.ModelManager
import com.agentx.app.model.ratelimit.RateLimitManager
import com.agentx.app.skills.SkillManager
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.ToolRegistry
import com.agentx.app.tools.ToolRouter

/**
 * Wires the Agent Core: loop, specialized registry, Main Agent, and orchestrator.
 * Model Gateway, Tool System, Context Engine, Prompt Manager and the Skills
 * registry are taken from the container; nothing is hardcoded.
 */
class AgentModule(
    /** Fallback budgets, used when none are registered in the container. */
    private val timeouts: AgentTimeouts = AgentTimeouts.DEFAULT,
    /**
     * Controlled model fallback configuration. Disabled unless the composition
     * root explicitly enables it and declares per-role chains.
     */
    private val fallbackPolicy: ModelFallbackPolicy = ModelFallbackPolicy.DISABLED,
) : ForgeModule {

    override val id: String = "agent"

    override fun initialize(context: ModuleContext) {
        val gateway = context.services.get<ModelGateway>(ServiceKeys.MODEL_GATEWAY) ?: DefaultModelGateway()
        val registry = context.services.get<ToolRegistry>(ServiceKeys.TOOL_REGISTRY) ?: DefaultToolRegistry()
        val router = context.services.get<ToolRouter>(ServiceKeys.TOOL_ROUTER) ?: DefaultToolRouter(registry)
        val contextEngine = context.services.get<ContextEngine>(ServiceKeys.CONTEXT_ENGINE)
        val prompts = context.services.get<PromptManager>(ServiceKeys.AGENT_PROMPTS)
        val skills = context.services.get<SkillManager>(ServiceKeys.SKILLS)
        // The engine is resolved once here so the same instance backs the run
        // context and the skill-context resolver.
        val engine = contextEngine ?: DefaultContextEngine()
        val skillContext = skills?.let { SkillContextProvider(skills, engine) }
        val sessionStore = context.services.get<AgentSessionStore>(ServiceKeys.AGENT_SESSION_STORE)
            ?: InMemoryAgentSessionStore()
        val conversationStore = context.services.get<ConversationStore>(ServiceKeys.AGENT_CONVERSATION_STORE)
            ?: InMemoryConversationStore()
        // The Model Manager owns the live provider connections. Reading them
        // through this lambda (rather than a snapshot) lets a role resolve its
        // own provider as soon as that provider is connected, beside the active
        // one. When no manager is registered the resolver sees no connections
        // and every role falls back to the active model, exactly as before.
        val modelManager = context.services.get<ModelManager>(ServiceKeys.MODEL_MANAGER)
        // Settings owns the per-role model configuration through this registry; the
        // resolver reads it live, so a change takes effect on the next run without
        // rebuilding the agent core. When no registry is registered the resolver
        // keeps the built-in default mapping.
        val roleModels = context.services.get<AgentRoleModelRegistry>(ServiceKeys.AGENT_ROLE_MODELS)
        // Built once so the agent core and the fallback layer share the same
        // resolver, capability registry and rate-limit admission control.
        val modelResolver = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { modelManager?.connections().orEmpty() },
            livePreferences = roleModels?.let { registry -> { registry.preferences() } },
            capabilityRegistry = context.services.get<ModelCapabilityRegistry>(
                ServiceKeys.MODEL_CAPABILITY_REGISTRY,
            ) ?: InMemoryModelCapabilityRegistry.DEFAULT,
            // The same admission control the gateway reserves against: the
            // resolver only asks whether a request is allowed, it never
            // reserves quota. A model blocked by its rate limit is reported
            // as ineligible instead of being silently replaced.
            rateLimitManager = context.services.get<RateLimitManager>(ServiceKeys.RATE_LIMIT_MANAGER),
        )
        val assembled = assemble(
            gateway = gateway,
            registry = registry,
            router = router,
            contextEngine = engine,
            // The container owns the budgets when the app registers them, so one
            // configuration drives the orchestrator, the loop and the tool system.
            timeouts = context.services.get<AgentTimeouts>(ServiceKeys.AGENT_TIMEOUTS) ?: timeouts,
            prompts = prompts,
            skillContext = skillContext,
            sessions = sessionStore,
            conversations = conversationStore,
            // The app boots with the target role → model mapping in place; every
            // role falls back to the active model until its provider is connected.
            modelResolver = modelResolver,
            fallbackPolicy = fallbackPolicy,
        )
        context.services.register(ServiceKeys.AGENT_ORCHESTRATOR, assembled.orchestrator)
        context.services.register(ServiceKeys.AGENT_REGISTRY, assembled.specialized)
        context.services.register(ServiceKeys.AGENT_HISTORY, assembled.history)
    }

    companion object {
        /**
         * Builds the agent core over the supplied gateway, tools and context
         * engine. Passing no engine gives the agent the default one, which
         * still budgets and redacts context but has no workspace attached.
         *
         * [prompts] and [skillContext] are optional so tests and previews keep
         * working with the built-in defaults and no skills.
         */
        fun assemble(
            gateway: ModelGateway,
            registry: ToolRegistry,
            router: ToolRouter,
            contextEngine: ContextEngine? = null,
            timeouts: AgentTimeouts = AgentTimeouts.DEFAULT,
            prompts: PromptManager? = null,
            skillContext: SkillContextResolver? = null,
            sessions: AgentSessionStore = InMemoryAgentSessionStore(),
            conversations: ConversationStore = InMemoryConversationStore(),
            modelResolver: AgentModelResolver = AgentModelResolver(),
            fallbackPolicy: ModelFallbackPolicy = ModelFallbackPolicy.DISABLED,
        ): AgentRuntime {
            val engine = contextEngine ?: DefaultContextEngine()
            val bridge = AgentToolBridge(registry)
            val loop = AgentLoop(
                gateway = gateway,
                toolRouter = router,
                bridge = bridge,
                runContexts = RunContextFactory.of(engine),
                prompts = prompts,
                skillContext = skillContext,
                timeouts = timeouts,
                modelFallback = ModelFallback(policy = { fallbackPolicy }, resolver = modelResolver),
            )
            val specialized = SpecializedAgentFactory(
                loop = loop,
                bridge = bridge,
                availableTools = { registry.names() },
            ).createAll()
            val mainAgent = MainAgent(loop = loop, bridge = bridge)
            val history = ConversationHistory(conversations = conversations, sessions = sessions)
            val orchestrator = DefaultAgentOrchestrator(
                mainAgent = mainAgent,
                specialized = specialized,
                sessions = sessions,
                contextEngine = engine,
                history = history,
                timeouts = timeouts,
                modelResolver = modelResolver,
            )
            return AgentRuntime(
                orchestrator = orchestrator,
                specialized = specialized,
                mainAgent = mainAgent,
                history = history,
            )
        }
    }
}

data class AgentRuntime(
    val orchestrator: AgentOrchestrator,
    val specialized: com.agentx.app.agent.specialized.SpecializedAgentRegistry,
    val mainAgent: MainAgent,
    val history: ConversationHistory = ConversationHistory(),
)
