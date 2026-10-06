package com.agentx.app.agent

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.model.AgentModelPreferences
import com.agentx.app.agent.model.AgentModelProviders
import com.agentx.app.agent.model.AgentModelResolver
import com.agentx.app.agent.model.AgentRoleModelRegistry
import com.agentx.app.agent.model.DefaultAgentRoleModelRepository
import com.agentx.app.agent.model.InMemoryAgentRoleModelStore
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-role assignments saved through Settings must reach the provider that
 * actually serves them. The registry is the same source of truth the resolver
 * consumes, and each scripted provider records the requests it received, so the
 * assertions are about real routing rather than constructors.
 */
class SettingsRoleRoutingTest {

    private fun config(providerId: String, model: String) = ModelConfig(
        providerId = providerId,
        baseUrl = "https://$providerId.example/v1",
        model = model,
        connectionKind = testDomain(providerId),
    )

    private fun delegate(role: AgentRole, task: String) = response(
        "",
        toolCall(
            AgentProtocol.DELEGATE_TOOL,
            AgentProtocol.ARG_ROLE to role.name,
            AgentProtocol.ARG_TASK to task,
            AgentProtocol.ARG_OBJECTIVE to task,
        ),
    )

    private fun finish(summary: String) = response(
        "",
        toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to summary),
    )

    @Test
    fun `main and coder settings select different providers at run time`() = runAgent {
        val gemini = ScriptedModelProvider(
            mapOf(AgentRole.MAIN to mutableListOf(delegate(AgentRole.CODER, "Patch it"), finish("patched"))),
            id = AgentModelProviders.GEMINI,
        )
        val local = ScriptedModelProvider(
            mapOf(AgentRole.CODER to mutableListOf(finish("wrote the fix"))),
            id = AgentModelProviders.OPENAI_COMPATIBLE,
        )
        val gateway = DefaultModelGateway()
        gateway.register(gemini)
        gateway.register(local)

        val registry = AgentRoleModelRegistry(
            DefaultAgentRoleModelRepository(InMemoryAgentRoleModelStore()),
        )
        registry.save(AgentRole.MAIN, AgentModelProviders.GEMINI, "gemini-3.5-flash")
        registry.save(AgentRole.CODER, AgentModelProviders.OPENAI_COMPATIBLE, "qwen2.5-coder-14b")

        val connections = mapOf(
            AgentModelProviders.GEMINI to config(AgentModelProviders.GEMINI, "gemini-3.5-flash"),
            AgentModelProviders.OPENAI_COMPATIBLE to
                config(AgentModelProviders.OPENAI_COMPATIBLE, "qwen2.5-coder-14b"),
        )
        val resolver = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { connections },
            livePreferences = { registry.preferences() },
        )

        val runtime = AgentModule.assemble(
            gateway = gateway,
            registry = DefaultToolRegistry(),
            router = DefaultToolRouter(DefaultToolRegistry()),
            modelResolver = resolver,
        )
        val result = runtime.orchestrator.run(
            request = AgentRunRequest(prompt = "fix it"),
            modelConfig = config(AgentModelProviders.GEMINI, "gemini-3.5-flash"),
            sink = CollectingEventSink(),
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals("patched", result.summary)
        assertEquals(2, gemini.requests.size)
        assertEquals(1, local.requests.size)
        assertTrue(gemini.requests.all { it.config.providerId == AgentModelProviders.GEMINI })
        assertTrue(local.requests.all { it.config.providerId == AgentModelProviders.OPENAI_COMPATIBLE })
        assertEquals("qwen2.5-coder-14b", local.requests.single().config.model)
    }

    @Test
    fun `changing a role in settings changes the provider selected at run time`() = runAgent {
        val local = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(delegate(AgentRole.CODER, "Patch it"), finish("done")),
                AgentRole.CODER to mutableListOf(finish("wrote the fix")),
            ),
            id = AgentModelProviders.OPENAI_COMPATIBLE,
        )
        val groq = ScriptedModelProvider(
            mapOf(AgentRole.CODER to mutableListOf(finish("wrote the fix"))),
            id = AgentModelProviders.GROQ,
        )
        val gateway = DefaultModelGateway()
        gateway.register(local)
        gateway.register(groq)

        val registry = AgentRoleModelRegistry(
            DefaultAgentRoleModelRepository(InMemoryAgentRoleModelStore()),
        )
        // Before the Settings change the Coder points at the local provider.
        registry.save(AgentRole.CODER, AgentModelProviders.OPENAI_COMPATIBLE, "devstral-24b")
        val connections = mapOf(
            AgentModelProviders.OPENAI_COMPATIBLE to
                config(AgentModelProviders.OPENAI_COMPATIBLE, "devstral-24b"),
            AgentModelProviders.GROQ to config(AgentModelProviders.GROQ, "llama-3.1-8b-instant"),
        )
        val resolver = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { connections },
            livePreferences = { registry.preferences() },
        )
        val runtime = AgentModule.assemble(
            gateway = gateway,
            registry = DefaultToolRegistry(),
            router = DefaultToolRouter(DefaultToolRegistry()),
            modelResolver = resolver,
        )

        // The user reassigns the Coder to the API provider in Settings before the run.
        registry.save(AgentRole.CODER, AgentModelProviders.GROQ, "llama-3.1-8b-instant")

        val result = runtime.orchestrator.run(
            request = AgentRunRequest(prompt = "patch it"),
            modelConfig = config(AgentModelProviders.GROQ, "llama-3.1-8b-instant"),
            sink = CollectingEventSink(),
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        // MAIN stays on the local default; the reassigned Coder now runs on Groq.
        assertEquals(2, local.requests.size)
        assertTrue(local.requests.all { it.config.providerId == AgentModelProviders.OPENAI_COMPATIBLE })
        assertEquals(1, groq.requests.size)
        assertEquals("llama-3.1-8b-instant", groq.requests.single().config.model)
        assertTrue(groq.requests.all { it.config.providerId == AgentModelProviders.GROQ })
    }
}
