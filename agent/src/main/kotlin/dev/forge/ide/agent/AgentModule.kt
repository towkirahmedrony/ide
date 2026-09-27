package dev.forge.ide.agent

import dev.forge.ide.agent.domain.AgentContextSource
import dev.forge.ide.agent.main.MainAgent
import dev.forge.ide.agent.orchestrator.AgentOrchestrator
import dev.forge.ide.agent.orchestrator.DefaultAgentOrchestrator
import dev.forge.ide.agent.orchestrator.InMemoryAgentSessionStore
import dev.forge.ide.agent.runtime.AgentLoop
import dev.forge.ide.agent.specialized.SpecializedAgentFactory
import dev.forge.ide.agent.tools.AgentToolBridge
import dev.forge.ide.core.foundation.ServiceKeys
import dev.forge.ide.core.module.ForgeModule
import dev.forge.ide.core.module.ModuleContext
import dev.forge.ide.model.DefaultModelGateway
import dev.forge.ide.model.ModelGateway
import dev.forge.ide.tools.DefaultToolRegistry
import dev.forge.ide.tools.DefaultToolRouter
import dev.forge.ide.tools.ToolRegistry
import dev.forge.ide.tools.ToolRouter

/**
 * Wires the Agent Core: loop, specialized registry, Main Agent, and orchestrator.
 * Model Gateway and Tool System are taken from the container; nothing is hardcoded.
 */
class AgentModule(
    private val contextSource: AgentContextSource? = null,
    private val timeoutMillis: Long = DefaultAgentOrchestrator.DEFAULT_TIMEOUT_MILLIS,
) : ForgeModule {

    override val id: String = "agent"

    override fun initialize(context: ModuleContext) {
        val gateway = context.services.get<ModelGateway>(ServiceKeys.MODEL_GATEWAY) ?: DefaultModelGateway()
        val registry = context.services.get<ToolRegistry>(ServiceKeys.TOOL_REGISTRY) ?: DefaultToolRegistry()
        val router = context.services.get<ToolRouter>(ServiceKeys.TOOL_ROUTER) ?: DefaultToolRouter(registry)
        val assembled = assemble(gateway, registry, router, contextSource, timeoutMillis)
        context.services.register(ServiceKeys.AGENT_ORCHESTRATOR, assembled.orchestrator)
        context.services.register(ServiceKeys.AGENT_REGISTRY, assembled.specialized)
    }

    companion object {
        fun assemble(
            gateway: ModelGateway,
            registry: ToolRegistry,
            router: ToolRouter,
            contextSource: AgentContextSource? = null,
            timeoutMillis: Long = DefaultAgentOrchestrator.DEFAULT_TIMEOUT_MILLIS,
        ): AgentRuntime {
            val bridge = AgentToolBridge(registry)
            val loop = AgentLoop(gateway = gateway, toolRouter = router, bridge = bridge)
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
                contextSource = contextSource,
                defaultTimeoutMillis = timeoutMillis,
            )
            return AgentRuntime(orchestrator = orchestrator, specialized = specialized, mainAgent = mainAgent)
        }
    }
}

data class AgentRuntime(
    val orchestrator: AgentOrchestrator,
    val specialized: dev.forge.ide.agent.specialized.SpecializedAgentRegistry,
    val mainAgent: MainAgent,
)
