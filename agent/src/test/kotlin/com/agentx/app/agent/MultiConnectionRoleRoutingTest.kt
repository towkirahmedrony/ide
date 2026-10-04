package com.agentx.app.agent

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.model.AgentModelPreferences
import com.agentx.app.agent.model.AgentModelProviders
import com.agentx.app.agent.model.AgentModelResolver
import com.agentx.app.agent.model.RoleModelPreference
import com.agentx.app.model.ModelConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The multi-agent product requirement: MAIN, CODER, REVIEWER, EXPLORER and
 * DEBUGGER must each be able to run on their own independent connection at the
 * same time, addressed by connection identity — including two connections that
 * share one provider family (a custom Devstral endpoint and a local Ollama Qwen).
 *
 * The resolver is pure, so this asserts exactly what a running agent would
 * receive, with no network and no gateway.
 */
class MultiConnectionRoleRoutingTest {

    private fun config(connectionId: String, providerId: String, model: String) = ModelConfig(
        providerId = providerId,
        baseUrl = "https://$connectionId.example/v1",
        model = model,
        connectionId = connectionId,
    )

    private val main = config("main-devstral", AgentModelProviders.OPENAI_COMPATIBLE, "devstral-24b")
    private val coder = config("coder-cerebras", AgentModelProviders.CEREBRAS, "llama3.1-8b")
    private val reviewer = config("reviewer-gemini", AgentModelProviders.GEMINI, "gemini-3.5-flash")
    private val explorer = config("explorer-groq", AgentModelProviders.GROQ, "llama-3.3-70b-versatile")
    private val debugger = config("debugger-ollama", AgentModelProviders.OPENAI_COMPATIBLE, "qwen2.5-coder-14b")

    private val all = mapOf(
        main.connectionId to main,
        coder.connectionId to coder,
        reviewer.connectionId to reviewer,
        explorer.connectionId to explorer,
        debugger.connectionId to debugger,
    )

    private val preferences = AgentModelPreferences(
        mapOf(
            AgentRole.MAIN to RoleModelPreference(
                AgentModelProviders.OPENAI_COMPATIBLE, "devstral-24b", connectionId = main.connectionId,
            ),
            AgentRole.CODER to RoleModelPreference(
                AgentModelProviders.CEREBRAS, "llama3.1-8b", connectionId = coder.connectionId,
            ),
            AgentRole.REVIEWER to RoleModelPreference(
                AgentModelProviders.GEMINI, "gemini-3.5-flash", connectionId = reviewer.connectionId,
            ),
            AgentRole.EXPLORER to RoleModelPreference(
                AgentModelProviders.GROQ, "llama-3.3-70b-versatile", connectionId = explorer.connectionId,
            ),
            AgentRole.DEBUGGER to RoleModelPreference(
                AgentModelProviders.OPENAI_COMPATIBLE, "qwen2.5-coder-14b", connectionId = debugger.connectionId,
            ),
        ),
    )

    private fun resolver(connections: Map<String, ModelConfig> = all) = AgentModelResolver(
        preferences = preferences,
        connections = { connections },
    )

    @Test
    fun `all five roles resolve to their own independent connection`() {
        val resolver = resolver()
        assertEquals(main, resolver.resolve(AgentRole.MAIN, main))
        assertEquals(coder, resolver.resolve(AgentRole.CODER, coder))
        assertEquals(reviewer, resolver.resolve(AgentRole.REVIEWER, reviewer))
        assertEquals(explorer, resolver.resolve(AgentRole.EXPLORER, explorer))
        assertEquals(debugger, resolver.resolve(AgentRole.DEBUGGER, debugger))
    }

    @Test
    fun `two roles on the same provider family stay on different connections`() {
        val resolver = resolver()
        val mainConfig = resolver.resolve(AgentRole.MAIN, main)
        val debuggerConfig = resolver.resolve(AgentRole.DEBUGGER, main)

        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE, mainConfig.providerId)
        assertEquals(AgentModelProviders.OPENAI_COMPATIBLE, debuggerConfig.providerId)
        assertNotEquals(mainConfig.connectionId, debuggerConfig.connectionId)
        assertNotEquals(mainConfig.baseUrl, debuggerConfig.baseUrl)
        assertNotEquals(mainConfig.model, debuggerConfig.model)
    }

    @Test
    fun `disconnecting one connection falls back for it and leaves the others untouched`() {
        // DEBUGGER's connection is gone; the other four are still connected.
        val remaining = all - debugger.connectionId
        val resolver = resolver(remaining)

        assertEquals(debuggerConnectionFallback(), resolver.resolve(AgentRole.DEBUGGER, main))
        assertEquals(main, resolver.resolve(AgentRole.MAIN, main))
        assertEquals(coder, resolver.resolve(AgentRole.CODER, coder))
        assertEquals(reviewer, resolver.resolve(AgentRole.REVIEWER, reviewer))
        assertEquals(explorer, resolver.resolve(AgentRole.EXPLORER, explorer))
    }

    @Test
    fun `a role with no explicit connection still resolves by provider family`() {
        val preferences = AgentModelPreferences(
            mapOf(
                AgentRole.EXPLORER to RoleModelPreference(AgentModelProviders.GROQ, "llama-3.3-70b-versatile"),
            ),
        )
        val resolver = AgentModelResolver(preferences = preferences, connections = { all })

        val resolved = resolver.resolve(AgentRole.EXPLORER, main)
        assertEquals(explorer.connectionId, resolved.connectionId)
        assertEquals(AgentModelProviders.GROQ, resolved.providerId)
        assertTrue(resolved.metadata.isEmpty() || resolved.model == "llama-3.3-70b-versatile")
    }

    /** A role whose connection is gone falls back to the active configuration. */
    private fun debuggerConnectionFallback(): ModelConfig = main
}
