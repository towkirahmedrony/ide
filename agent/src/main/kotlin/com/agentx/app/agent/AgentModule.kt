package com.agentx.app.agent

import com.agentx.app.agent.main.MainAgent
import com.agentx.app.agent.orchestrator.AgentOrchestrator
import com.agentx.app.agent.orchestrator.DefaultAgentOrchestrator
import com.agentx.app.agent.orchestrator.InMemoryAgentSessionStore
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.specialized.SpecializedAgentFactory
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.context.ContextEngine
import com.agentx.app.context.DefaultContextEngine
import com.agentx.app.context.RunContextFactory
import com.agentx.app.core.foundation.ServiceKeys
import com.agentx.app.core.module.ForgeModule
import com.agentx.app.core.module.ModuleContext
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelGateway
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.ToolRegistry
import com.agentx.app.tools.ToolRouter

/**
 * Wires the Agent Core: loop, specialized registry, Main Agent, and orchestrator.
 * Model Gateway, Tool System and Context Engine are taken from the container;
 * nothing is hardcoded.
 */
class AgentModule(
    private val timeoutMillis: Long = DefaultAgentOrchestrator.DEFAULT_TIMEOUT_MILLIS,
) : ForgeModule {

    override val id: String = "agent"

    override fun initialize(context: ModuleContext) {
        val gateway = context.services.get<ModelGateway>(ServiceKeys.MODEL_GATEWAY) ?: DefaultModelGateway()
        val registry = context.services.get<ToolRegistry>(ServiceKeys.TOOL_REGISTRY) ?: DefaultToolRegistry()
        val router = context.services.get<ToolRouter>(ServiceKeys.TOOL_ROUTER) ?: DefaultToolRouter(registry)
        val contextEngine = context.services.get<ContextEngine>(ServiceKeys.CONTEXT_ENGINE)
        val assembled = assemble(gateway, registry, router, contextEngine, timeoutMillis)
        context.services.register(ServiceKeys.AGENT_ORCHESTRATOR, assembled.orchestrator)
        context.services.register(ServiceKeys.AGENT_REGISTRY, assembled.specialized)
    }

    companion object {
        /**
         * Builds the agent core over the supplied gateway, tools and context
         * engine. Passing no engine gives the agent the default one, which
         * still budgets and redacts context but has no workspace attached.
         */
        fun assemble(
            gateway: ModelGateway,
            registry: ToolRegistry,
            router: ToolRouter,
            contextEngine: ContextEngine? = null,
            timeoutMillis: Long = DefaultAgentOrchestrator.DEFAULT_TIMEOUT_MILLIS,
        ): AgentRuntime {
            val engine = contextEngine ?: DefaultContextEngine()
            val bridge = AgentToolBridge(registry)
            val loop = AgentLoop(
                gateway = gateway,
                toolRouter = router,
                bridge = bridge,
                runContexts = RunContextFactory.of(engine),
            )
            val specialized = SpecializedAgentFactory(
                loop = loop,
                bridge = bridge,
                availableTools = { registry.names() },
            ).createAll()
            val mainAgent = MainAgent(loop = loop, bridge = bridge)
            val orchestrator = DefaultAgentOrchestrator(
                mainAgent = mainAgent,
                specialized = specialized,
                sessions = InMemoryAgentSessionStore(),
                contextEngine = engine,
                defaultTimeoutMillis = timeoutMillis,
            )
            return AgentRuntime(orchestrator = orchestrator, specialized = specialized, mainAgent = mainAgent)
        }
    }
}

data class AgentRuntime(
    val orchestrator: AgentOrchestrator,
    val specialized: com.agentx.app.agent.specialized.SpecializedAgentRegistry,
    val mainAgent: MainAgent,
)
