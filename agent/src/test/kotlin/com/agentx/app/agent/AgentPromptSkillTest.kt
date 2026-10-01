package com.agentx.app.agent

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.prompt.PromptManager
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.runtime.AgentLoopRequest
import com.agentx.app.agent.runtime.ResumedPermission
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.context.ContextBudget
import com.agentx.app.context.DefaultContextEngine
import com.agentx.app.context.SkillContext
import com.agentx.app.context.SkillContextProvider
import com.agentx.app.context.SkillContextResolver
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelRole
import com.agentx.app.skills.DefaultSkillManager
import com.agentx.app.skills.InMemorySkillStore
import com.agentx.app.skills.SkillDefinition
import com.agentx.app.skills.SkillManager
import com.agentx.app.skills.SkillSource
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import com.agentx.app.tools.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    // --- Main Agent skills through the real skill pipeline ------------------

    private fun skill(
        id: String,
        roles: Set<String>,
        instructions: String = "follow the $id instructions",
    ) = SkillDefinition(
        id = id,
        name = "Skill $id",
        description = "Description for $id",
        instructions = instructions,
        source = SkillSource.BUILTIN,
        roles = roles,
    )

    private fun skillsManager(vararg definitions: SkillDefinition) =
        DefaultSkillManager(builtins = definitions.toList(), store = InMemorySkillStore())

    private fun loopWith(
        provider: ScriptedModelProvider,
        prompts: PromptManager,
        skills: SkillManager,
        registry: ToolRegistry = DefaultToolRegistry(),
    ): AgentLoop = AgentLoop(
        gateway = DefaultModelGateway().also { it.register(provider) },
        toolRouter = DefaultToolRouter(registry),
        bridge = AgentToolBridge(registry),
        prompts = prompts,
        skillContext = SkillContextProvider(skills, DefaultContextEngine()),
    )

    @Test
    fun `a skill enabled and assigned to MAIN reaches the main agent system message`() = runAgent {
        val prompts = PromptManager()
        val skills = skillsManager(skill("main-rules", roles = setOf("MAIN")))
        skills.refresh()
        skills.setEnabled("main-rules", true)
        val provider = scripted(AgentRole.MAIN)

        loopWith(provider, prompts, skills).run(
            request = request(AgentRole.MAIN),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        val system = systemMessage(provider)
        assertTrue(system.contains("# Skills"), system)
        assertTrue(system.contains("Skill main-rules"), system)
        assertTrue(system.contains("follow the main-rules instructions"), system)
    }

    @Test
    fun `a skill not assigned to MAIN never enters the main agent system message`() = runAgent {
        val prompts = PromptManager()
        val skills = skillsManager(skill("coder-only", roles = setOf("CODER")))
        skills.refresh()
        skills.setEnabled("coder-only", true)
        val provider = scripted(AgentRole.MAIN)

        loopWith(provider, prompts, skills).run(
            request = request(AgentRole.MAIN),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        val system = systemMessage(provider)
        assertTrue(!system.contains("# Skills"), system)
        assertTrue(!system.contains("follow the coder-only instructions"), system)
    }

    @Test
    fun `a disabled skill never enters the main agent system message`() = runAgent {
        val prompts = PromptManager()
        val skills = skillsManager(skill("main-rules", roles = setOf("MAIN")))
        skills.refresh()
        // Discovered and assigned, but not enabled in Settings.
        val provider = scripted(AgentRole.MAIN)

        loopWith(provider, prompts, skills).run(
            request = request(AgentRole.MAIN),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        val system = systemMessage(provider)
        assertTrue(!system.contains("# Skills"), system)
        assertTrue(!system.contains("follow the main-rules instructions"), system)
    }

    @Test
    fun `a skill set to all agents reaches MAIN`() = runAgent {
        val prompts = PromptManager()
        val skills = skillsManager(skill("everywhere", roles = emptySet()))
        skills.refresh()
        skills.setEnabled("everywhere", true)
        val provider = scripted(AgentRole.MAIN)

        loopWith(provider, prompts, skills).run(
            request = request(AgentRole.MAIN),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        assertTrue(systemMessage(provider).contains("follow the everywhere instructions"))
    }

    // --- Permission pause and resume ---------------------------------------

    private fun permissionRequest(allowed: List<String>, permission: PermissionLevel) =
        request(AgentRole.MAIN).copy(allowedTools = allowed, permissionLevel = permission)

    @Test
    fun `a resumed run refreshes the prompt and skills without losing the conversation`() = runAgent {
        val prompts = PromptManager()
        prompts.save(AgentRole.MAIN, "PROMPT-BEFORE-PAUSE")
        val skills = skillsManager(
            skill("main-rules", roles = setOf("MAIN")),
            skill("review-rules", roles = setOf("MAIN")),
        )
        skills.refresh()
        skills.setEnabled("main-rules", true)
        // "review-rules" stays switched off until the run is parked.

        val guarded = RecordingTool(
            name = "guarded_write",
            capabilities = setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM),
            permission = ToolPermissionDecision.ASK,
            required = setOf(ToolPermissionLevel.WORKSPACE_WRITE),
        )
        val registry = DefaultToolRegistry().also { it.register(guarded) }
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("", toolCall("guarded_write", "path" to "a.kt", id = "c1")),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "done")),
                ),
            ),
        )
        val loop = loopWith(provider, prompts, skills, registry)
        val allowed = listOf("guarded_write", AgentProtocol.FINISH_TOOL)

        val paused = loop.run(
            request = permissionRequest(allowed, PermissionLevel.WORKSPACE_WRITE),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )
        assertEquals(AgentStatus.WAITING_FOR_PERMISSION, paused.status)
        val pending = assertNotNull(paused.pendingPermission)

        // Both requests before the resume saw the prompt and the single skill that
        // were configured at the time.
        val parkedSystem = provider.requests.first().messages.first { it.role == ModelRole.SYSTEM }.content
        assertTrue(parkedSystem.contains("PROMPT-BEFORE-PAUSE"), parkedSystem)
        assertTrue(!parkedSystem.contains("review-rules"), parkedSystem)

        // Settings change while the run is parked: a new prompt and a newly
        // enabled skill must both apply to the resumed request.
        prompts.save(AgentRole.MAIN, "PROMPT-AFTER-RESUME")
        skills.setEnabled("review-rules", true)

        val resumed = loop.run(
            request = permissionRequest(allowed, PermissionLevel.WORKSPACE_WRITE).copy(
                resumeContext = paused.resumeContext,
                resumePermission = ResumedPermission(
                    toolName = pending.toolName,
                    arguments = pending.arguments,
                    reason = pending.reason,
                    toolCallId = pending.toolCallId,
                    approved = true,
                ),
            ),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        assertEquals(AgentStatus.COMPLETED, resumed.status)
        assertEquals(1, guarded.invocations.size, "the approved tool runs exactly once")

        val messages = provider.requests.last().messages
        val systemMessages = messages.filter { it.role == ModelRole.SYSTEM }
        assertEquals(1, systemMessages.size, "a resumed run has exactly one system message")
        assertEquals(messages.first(), systemMessages.single(), "the system message stays first")
        val system = systemMessages.single().content
        assertTrue(system.contains("PROMPT-AFTER-RESUME"), system)
        assertTrue(!system.contains("PROMPT-BEFORE-PAUSE"), system)
        assertTrue(system.contains("follow the main-rules instructions"), system)
        assertTrue(system.contains("follow the review-rules instructions"), system)

        // The conversation and the approved tool call survive, exactly once each.
        assertEquals(1, messages.count { it.role == ModelRole.TOOL && it.toolCallId == "c1" })
        assertEquals(1, messages.count { it.role == ModelRole.ASSISTANT && it.toolCalls.any { call -> call.id == "c1" } })
    }

    @Test
    fun `a resumed run keeps the parked conversation when nothing changed`() = runAgent {
        val prompts = PromptManager()
        val skills = skillsManager(skill("main-rules", roles = setOf("MAIN")))
        skills.refresh()
        skills.setEnabled("main-rules", true)

        val guarded = RecordingTool(
            name = "guarded_write",
            capabilities = setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM),
            permission = ToolPermissionDecision.ASK,
            required = setOf(ToolPermissionLevel.WORKSPACE_WRITE),
        )
        val registry = DefaultToolRegistry().also { it.register(guarded) }
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("", toolCall("guarded_write", "path" to "a.kt", id = "c1")),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "done")),
                ),
            ),
        )
        val loop = loopWith(provider, prompts, skills, registry)
        val allowed = listOf("guarded_write", AgentProtocol.FINISH_TOOL)

        val paused = loop.run(
            request = permissionRequest(allowed, PermissionLevel.WORKSPACE_WRITE),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )
        val pending = assertNotNull(paused.pendingPermission)

        val resumed = loop.run(
            request = permissionRequest(allowed, PermissionLevel.WORKSPACE_WRITE).copy(
                resumeContext = paused.resumeContext,
                resumePermission = ResumedPermission(
                    toolName = pending.toolName,
                    arguments = pending.arguments,
                    reason = pending.reason,
                    toolCallId = pending.toolCallId,
                    approved = false,
                ),
            ),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        assertEquals(AgentStatus.COMPLETED, resumed.status)
        assertTrue(guarded.invocations.isEmpty(), "a denied tool never runs")
        val messages = provider.requests.last().messages
        // The denial is reported to the model as a tool result, not by restarting.
        assertEquals(1, messages.count { it.role == ModelRole.SYSTEM })
        assertEquals(1, messages.count { it.role == ModelRole.TOOL && it.toolCallId == "c1" })
        assertTrue(messages.filter { it.role == ModelRole.SYSTEM }.single().content.contains("Skill main-rules"))
    }
}
