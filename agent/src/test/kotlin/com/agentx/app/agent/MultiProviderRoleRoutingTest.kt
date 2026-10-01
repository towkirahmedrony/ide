package com.agentx.app.agent

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.model.AgentModelPreferences
import com.agentx.app.agent.model.AgentModelProviders
import com.agentx.app.agent.model.AgentModelResolver
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The role → provider mapping resolved by [AgentModelResolver] must reach the
 * provider that actually serves it, with Gemini, Groq and a local
 * OpenAI-compatible model all registered in the same [DefaultModelGateway].
 * Each scripted provider records the requests it received, so the assertions are
 * about real routing, not about constructors.
 */
class MultiProviderRoleRoutingTest {

    private fun config(providerId: String, model: String) = ModelConfig(
        providerId = providerId,
        baseUrl = "https://$providerId.example/v1",
        model = model,
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
    fun `main and coder run on different simultaneously registered providers`() = runAgent {
        val gemini = ScriptedModelProvider(
            mapOf(AgentRole.MAIN to mutableListOf(delegate(AgentRole.CODER, "Patch the bug"), finish("patched"))),
            id = AgentModelProviders.GEMINI,
        )
        val local = ScriptedModelProvider(
            mapOf(AgentRole.CODER to mutableListOf(finish("wrote the fix"))),
            id = AgentModelProviders.OPENAI_COMPATIBLE,
        )
        val groq = ScriptedModelProvider(
            mapOf(AgentRole.EXPLORER to mutableListOf(finish("explored"))),
            id = AgentModelProviders.GROQ,
        )

        val gateway = DefaultModelGateway()
        gateway.register(gemini)
        gateway.register(local)
        gateway.register(groq)

        val connections = mapOf(
            AgentModelProviders.GEMINI to config(AgentModelProviders.GEMINI, "gemini-1.5-pro"),
            AgentModelProviders.GROQ to config(
                AgentModelProviders.GROQ,
                "llama-3.3-70b-versatile",
            ),
            AgentModelProviders.OPENAI_COMPATIBLE to config(
                AgentModelProviders.OPENAI_COMPATIBLE,
                "qwen2.5-coder-14b",
            ),
        )
        val resolver = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { connections },
        )

        // The default/active model is Groq; MAIN overrides it with Gemini and the
        // delegated CODER overrides it with the local OpenAI-compatible model.
        val active = config(AgentModelProviders.GROQ, "llama-3.3-70b-versatile")
        val runtime = AgentModule.assemble(
            gateway = gateway,
            registry = DefaultToolRegistry(),
            router = DefaultToolRouter(DefaultToolRegistry()),
            modelResolver = resolver,
        )

        val result = runtime.orchestrator.run(
            request = AgentRunRequest(prompt = "fix it"),
            modelConfig = active,
            sink = CollectingEventSink(),
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals("patched", result.summary)
        assertEquals(2, gemini.requests.size)
        assertEquals(1, local.requests.size)
        assertTrue(gemini.requests.all { it.config.providerId == AgentModelProviders.GEMINI })
        assertTrue(local.requests.all { it.config.providerId == AgentModelProviders.OPENAI_COMPATIBLE })
        assertEquals("qwen2.5-coder-14b", local.requests.single().config.model)
        assertTrue(groq.requests.isEmpty(), "the active provider is not used when a role resolves its own")
    }

    @Test
    fun `an unconnected role provider falls back to the active provider`() = runAgent {
        val gemini = ScriptedModelProvider(
            mapOf(AgentRole.MAIN to mutableListOf(finish("done"))),
            id = AgentModelProviders.GEMINI,
        )
        val gateway = DefaultModelGateway()
        gateway.register(gemini)

        // Only Gemini is connected; MAIN's preference resolves to it.
        val connections = mapOf(
            AgentModelProviders.GEMINI to config(AgentModelProviders.GEMINI, "gemini-1.5-pro"),
        )
        val resolver = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { connections },
        )

        val active = config(AgentModelProviders.OPENAI_COMPATIBLE, "qwen2.5-coder-14b")
        val result = AgentModule.assemble(
            gateway = gateway,
            registry = DefaultToolRegistry(),
            router = DefaultToolRouter(DefaultToolRegistry()),
            modelResolver = resolver,
        ).orchestrator.run(
            request = AgentRunRequest(prompt = "hello"),
            modelConfig = active,
            sink = CollectingEventSink(),
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals(1, gemini.requests.size)
        assertEquals(AgentModelProviders.GEMINI, gemini.requests.single().config.providerId)
    }
}
