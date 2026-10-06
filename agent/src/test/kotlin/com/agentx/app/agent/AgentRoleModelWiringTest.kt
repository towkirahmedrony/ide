package com.agentx.app.agent

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.model.AgentModelIds
import com.agentx.app.agent.model.AgentModelPreferences
import com.agentx.app.agent.model.AgentModelResolver
import com.agentx.app.agent.model.RoleModelPreference
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end wiring: the resolution performed by [AgentModelResolver] must reach
 * the actual model request of the Main Agent and of a delegated sub-agent. The
 * provider records every request it receives, so the assertions are about what
 * the loop really sent, not about constructors.
 */
class AgentRoleModelWiringTest {

    private fun runtime(resolver: AgentModelResolver, provider: ScriptedModelProvider) =
        AgentModule.assemble(
            gateway = DefaultModelGateway().also { it.register(provider) },
            registry = DefaultToolRegistry(),
            router = DefaultToolRouter(DefaultToolRegistry()),
            modelResolver = resolver,
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

    private fun finish(summary: String) =
        response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to summary))

    @Test
    fun `main agent receives the resolved main configuration`() = runAgent {
        val provider = ScriptedModelProvider(mapOf(AgentRole.MAIN to mutableListOf(response("done"))))
        val resolver = AgentModelResolver(
            AgentModelPreferences().with(AgentRole.MAIN, RoleModelPreference(testConfig().providerId)),
        )

        val result = runtime(resolver, provider).orchestrator.run(
            request = AgentRunRequest(prompt = "hello"),
            modelConfig = testConfig(),
            sink = CollectingEventSink(),
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        val request = provider.requests.single()
        assertEquals(AgentModelIds.DEVSTRAL_24B, request.config.model)
        assertEquals(testConfig().providerId, request.config.providerId)
        assertNotEquals(testConfig().model, request.config.model)
    }

    @Test
    fun `a delegated coder receives its own resolved configuration`() = runAgent {
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(AgentRole.CODER, "Patch the bug"),
                    finish("patched"),
                ),
                AgentRole.CODER to mutableListOf(finish("wrote the fix")),
            ),
        )
        val resolver = AgentModelResolver(
            AgentModelPreferences()
                .with(AgentRole.MAIN, RoleModelPreference(testConfig().providerId))
                .with(AgentRole.CODER, RoleModelPreference(testConfig().providerId, "coder-model")),
        )

        val result = runtime(resolver, provider).orchestrator.run(
            request = AgentRunRequest(prompt = "fix it"),
            modelConfig = testConfig(),
            sink = CollectingEventSink(),
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals("patched", result.summary)
        assertEquals(3, provider.requests.size)

        val mainRequest = provider.requests[0]
        val coderRequest = provider.requests[1]
        // The Main Agent keeps the catalog's default; the delegated Coder picks up its
        // own mapping, so the two roles are not collapsed onto one configuration.
        assertEquals(AgentModelIds.DEVSTRAL_24B, mainRequest.config.model)
        assertEquals("coder-model", coderRequest.config.model)
        assertNotEquals(mainRequest.config.model, coderRequest.config.model)
        // Both roles still resolve through the single registered provider.
        assertTrue(provider.requests.all { it.config.providerId == testConfig().providerId })
    }

    @Test
    fun `existing single-provider behavior is unchanged without role preferences`() = runAgent {
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(AgentRole.CODER, "Patch the bug"),
                    finish("patched"),
                ),
                AgentRole.CODER to mutableListOf(finish("wrote the fix")),
            ),
        )

        val result = runtime(AgentModelResolver(), provider).orchestrator.run(
            request = AgentRunRequest(prompt = "fix it"),
            modelConfig = testConfig(),
            sink = CollectingEventSink(),
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertTrue(provider.requests.isNotEmpty())
        assertTrue(provider.requests.all { it.config == testConfig() })
    }
}
