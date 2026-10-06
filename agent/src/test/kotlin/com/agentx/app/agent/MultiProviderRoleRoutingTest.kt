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
 * provider that actually serves it, with a local OpenAI-compatible model, an
 * API gateway and Groq all registered in the same [DefaultModelGateway]. Each
 * scripted provider records the requests it received, so the assertions are
 * about real routing, not about constructors.
 */
class MultiProviderRoleRoutingTest {

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
    fun `main and coder run on the local model while api providers stay idle`() = runAgent {
        val local = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(delegate(AgentRole.CODER, "Patch the bug"), finish("patched")),
                AgentRole.CODER to mutableListOf(finish("wrote the fix")),
            ),
            id = AgentModelProviders.OPENAI_COMPATIBLE,
        )
        val gemini = ScriptedModelProvider(mapOf(AgentRole.REVIEWER to mutableListOf(finish("reviewed"))), id = AgentModelProviders.GEMINI)
        val freeLlm = ScriptedModelProvider(
            mapOf(AgentRole.EXPLORER to mutableListOf(finish("explored"))),
            id = AgentModelProviders.FREELMAPI,
        )

        val gateway = DefaultModelGateway()
        gateway.register(local)
        gateway.register(gemini)
        gateway.register(freeLlm)

        val connections = mapOf(
            AgentModelProviders.OPENAI_COMPATIBLE to config(AgentModelProviders.OPENAI_COMPATIBLE, "devstral-24b"),
            AgentModelProviders.FREELMAPI to config(AgentModelProviders.FREELMAPI, "gemini-3.5-flash"),
            AgentModelProviders.GROQ to config(AgentModelProviders.GROQ, "llama-3.3-70b-versatile"),
        )
        val resolver = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { connections },
        )

        // The default/active model is Groq; both MAIN and the delegated CODER resolve
        // to their own local Devstral connection regardless.
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
        // MAIN delegates, CODER finishes, then MAIN finishes — all on the local model.
        assertEquals(3, local.requests.size)
        assertTrue(local.requests.all { it.config.providerId == AgentModelProviders.OPENAI_COMPATIBLE })
        assertTrue(local.requests.all { it.config.model == "devstral-24b" })
        assertTrue(gemini.requests.isEmpty(), "an API provider is not used when a local role resolves locally")
        assertTrue(freeLlm.requests.isEmpty(), "an API provider is not used when a local role resolves locally")
    }

    @Test
    fun `main stays on the local model even when an api provider is the active model`() = runAgent {
        // The active model is a remote API model, and an API gateway is connected:
        // MAIN is still bound to the local domain.
        val local = ScriptedModelProvider(
            mapOf(AgentRole.MAIN to mutableListOf(finish("done"))),
            id = AgentModelProviders.OPENAI_COMPATIBLE,
        )
        val gateway = DefaultModelGateway()
        gateway.register(local)

        val connections = mapOf(
            AgentModelProviders.OPENAI_COMPATIBLE to config(AgentModelProviders.OPENAI_COMPATIBLE, "devstral-24b"),
        )
        val resolver = AgentModelResolver(
            preferences = AgentModelPreferences.DEFAULT,
            connections = { connections },
        )

        val active = config(AgentModelProviders.GEMINI, "gemini-3.5-flash")
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
        assertEquals(1, local.requests.size)
        assertTrue(local.requests.all { it.config.providerId == AgentModelProviders.OPENAI_COMPATIBLE })
    }
}
