package com.agentx.app.agent

import com.agentx.app.agent.conversation.ConversationHistory
import com.agentx.app.agent.conversation.ConversationStore
import com.agentx.app.agent.conversation.InMemoryConversationStore
import com.agentx.app.agent.main.MainAgent
import com.agentx.app.agent.model.AgentModelPreferences
import com.agentx.app.agent.model.AgentModelResolver
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
            modelResolver = AgentModelResolver(AgentModelPreferences.DEFAULT),
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
