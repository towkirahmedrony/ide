package com.agentx.app.agent

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.prompt.PromptManager
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.runtime.AgentLoopRequest
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.context.ContextBudget
import com.agentx.app.context.SkillContext
import com.agentx.app.context.SkillContextResolver
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelRole
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import kotlin.test.Test
import kotlin.test.assertTrue

class AgentPromptSkillTest {

    private val skillResolver = object : SkillContextResolver {
        override suspend fun resolve(role: String, budget: ContextBudget): SkillContext =
            SkillContext(rendered = "SKILL-FOR-$role: follow the skill instructions")
    }

    private fun loop(provider: ScriptedModelProvider, prompts: PromptManager): AgentLoop {
        val registry = DefaultToolRegistry()
        val gateway = DefaultModelGateway().also { it.register(provider) }
        return AgentLoop(
            gateway = gateway,
            toolRouter = DefaultToolRouter(registry),
            bridge = AgentToolBridge(registry),
            prompts = prompts,
            skillContext = skillResolver,
        )
    }

    private fun request(role: AgentRole) = AgentLoopRequest(
        sessionId = "s",
        parentSessionId = if (role == AgentRole.MAIN) null else "parent",
        definition = AgentCatalog.definition(role),
        allowedTools = listOf(AgentProtocol.FINISH_TOOL),
        permissionLevel = AgentCatalog.definition(role).effectivePermission,
        maxSteps = 2,
        userPrompt = "go",
        objective = null,
        scopedContext = "",
        workspaceId = null,
        modelConfig = testConfig(),
    )

    private fun scripted(vararg roles: AgentRole): ScriptedModelProvider = ScriptedModelProvider(
        roles.associateWith {
            mutableListOf(
                response(
                    "",
                    toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "done"),
                ),
            )
        },
    )

    private fun systemMessage(provider: ScriptedModelProvider): String =
        provider.requests.first().messages.first { it.role == ModelRole.SYSTEM }.content

    @Test
    fun `sub-agent prompt and skills come from the central managers`() = runAgent {
        val prompts = PromptManager()
        prompts.save(AgentRole.CODER, "CUSTOM-CODER-PROMPT")
        val provider = scripted(AgentRole.CODER)

        loop(provider, prompts).run(
            request = request(AgentRole.CODER),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        val system = systemMessage(provider)
        assertTrue(system.contains("CUSTOM-CODER-PROMPT"), system)
        assertTrue(system.contains("SKILL-FOR-CODER"), system)
    }

    @Test
    fun `an agent without an override uses the built-in default`() = runAgent {
        val prompts = PromptManager()
        val provider = scripted(AgentRole.MAIN)

        loop(provider, prompts).run(
            request = request(AgentRole.MAIN),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        val system = systemMessage(provider)
        assertTrue(system.contains("Main Agent"), system)
        assertTrue(system.contains("SKILL-FOR-MAIN"), system)
    }

    @Test
    fun `an override for one role does not leak into another`() = runAgent {
        val prompts = PromptManager()
        prompts.save(AgentRole.REVIEWER, "REVIEWER-ONLY-PROMPT")
        val provider = scripted(AgentRole.CODER)

        loop(provider, prompts).run(
            request = request(AgentRole.CODER),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        val system = systemMessage(provider)
        assertTrue(!system.contains("REVIEWER-ONLY-PROMPT"), system)
    }
}
