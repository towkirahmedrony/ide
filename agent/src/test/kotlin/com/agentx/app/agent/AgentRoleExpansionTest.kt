package com.agentx.app.agent

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.conversation.AgentConversation
import com.agentx.app.agent.conversation.ConversationCodec
import com.agentx.app.agent.conversation.ConversationMessage
import com.agentx.app.agent.conversation.MessageContent
import com.agentx.app.agent.conversation.MessageMetadata
import com.agentx.app.agent.conversation.MessageRole
import com.agentx.app.agent.conversation.MessageStatus
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentSession
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.AgentTask
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.domain.SubAgentRequest
import com.agentx.app.agent.prompt.DefaultAgentPrompts
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.specialized.SpecializedAgentFactory
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.filesystem.ListDirectoryTool
import com.agentx.app.tools.filesystem.ReadFileTool
import com.agentx.app.tools.filesystem.SearchFilesTool
import com.agentx.app.tools.filesystem.WriteFileTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentRoleExpansionTest {

    private val expectedRoles = listOf(
        AgentRole.MAIN,
        AgentRole.EXPLORER,
        AgentRole.RESEARCHER,
        AgentRole.CODER,
        AgentRole.DEBUGGER,
        AgentRole.REVIEWER,
        AgentRole.TESTER,
        AgentRole.PLANNER,
        AgentRole.FAST_CODER,
        AgentRole.SECURITY_REVIEWER,
        AgentRole.DOCS,
        AgentRole.COMMIT_PR,
    )

    private val existingRoles = listOf(
        AgentRole.MAIN,
        AgentRole.EXPLORER,
        AgentRole.RESEARCHER,
        AgentRole.CODER,
        AgentRole.DEBUGGER,
        AgentRole.REVIEWER,
        AgentRole.TESTER,
    )

    private val newRoles = listOf(
        AgentRole.PLANNER,
        AgentRole.FAST_CODER,
        AgentRole.SECURITY_REVIEWER,
        AgentRole.DOCS,
        AgentRole.COMMIT_PR,
    )

    private val readOnlyRoles = listOf(
        AgentRole.EXPLORER,
        AgentRole.RESEARCHER,
        AgentRole.REVIEWER,
        AgentRole.PLANNER,
        AgentRole.SECURITY_REVIEWER,
    )

    @Test
    fun `all twelve roles exist in declared order`() {
        assertEquals(12, AgentRole.entries.size)
        assertEquals(expectedRoles, AgentRole.entries)
    }

    @Test
    fun `existing role names and ordinals remain compatible`() {
        existingRoles.forEachIndexed { index, role ->
            assertEquals(index, role.ordinal, role.name)
            assertEquals(role, AgentRole.valueOf(role.name))
        }
        assertEquals(AgentRole.MAIN, AgentRole.valueOf("MAIN"))
        assertEquals(AgentRole.EXPLORER, AgentRole.valueOf("EXPLORER"))
        assertEquals(AgentRole.RESEARCHER, AgentRole.valueOf("RESEARCHER"))
        assertEquals(AgentRole.CODER, AgentRole.valueOf("CODER"))
        assertEquals(AgentRole.DEBUGGER, AgentRole.valueOf("DEBUGGER"))
        assertEquals(AgentRole.REVIEWER, AgentRole.valueOf("REVIEWER"))
        assertEquals(AgentRole.TESTER, AgentRole.valueOf("TESTER"))
    }

    @Test
    fun `every role has a catalog definition with a matching role`() {
        assertEquals(12, AgentCatalog.all().size)
        AgentRole.entries.forEach { role ->
            val definition = AgentCatalog.definition(role)
            assertEquals(role, definition.role)
            assertTrue(definition.name.isNotBlank(), role.name)
            assertTrue(definition.maxSteps > 0, role.name)
        }
        newRoles.forEach { role ->
            assertNotNull(AgentCatalog.definition(role))
            assertEquals(role, AgentCatalog.all().single { it.role == role }.role)
        }
    }

    @Test
    fun `role prompts are non-empty`() {
        AgentRole.entries.forEach { role ->
            val prompt = DefaultAgentPrompts.forRole(role)
            assertTrue(prompt.isNotBlank(), role.name)
            assertEquals(prompt, AgentCatalog.definition(role).systemInstructions)
            assertTrue(DefaultAgentPrompts.describe(role).isNotBlank(), role.name)
        }
        assertTrue(DefaultAgentPrompts.all.size == 12)
        assertTrue(DefaultAgentPrompts.all.values.all { it.isNotBlank() })
    }

    @Test
    fun `role permissions match the existing permission model`() {
        assertEquals(PermissionLevel.WORKSPACE_WRITE, AgentCatalog.MAIN.permissionLevel)
        assertFalse(AgentCatalog.MAIN.isReadOnly)
        assertEquals(PermissionLevel.READ_ONLY, AgentCatalog.EXPLORER.permissionLevel)
        assertEquals(PermissionLevel.NETWORK, AgentCatalog.RESEARCHER.permissionLevel)
        assertEquals(PermissionLevel.WORKSPACE_WRITE, AgentCatalog.CODER.permissionLevel)
        assertEquals(PermissionLevel.COMMAND_EXECUTION, AgentCatalog.DEBUGGER.permissionLevel)
        assertEquals(PermissionLevel.READ_ONLY, AgentCatalog.REVIEWER.permissionLevel)
        assertEquals(PermissionLevel.COMMAND_EXECUTION, AgentCatalog.TESTER.permissionLevel)

        assertEquals(PermissionLevel.READ_ONLY, AgentCatalog.PLANNER.permissionLevel)
        assertTrue(AgentCatalog.PLANNER.isReadOnly)
        assertEquals(PermissionLevel.WORKSPACE_WRITE, AgentCatalog.FAST_CODER.permissionLevel)
        assertFalse(AgentCatalog.FAST_CODER.isReadOnly)
        assertEquals(PermissionLevel.READ_ONLY, AgentCatalog.SECURITY_REVIEWER.permissionLevel)
        assertTrue(AgentCatalog.SECURITY_REVIEWER.isReadOnly)
        assertEquals(PermissionLevel.WORKSPACE_WRITE, AgentCatalog.DOCS.permissionLevel)
        assertFalse(AgentCatalog.DOCS.isReadOnly)
        assertEquals(PermissionLevel.GIT_WRITE, AgentCatalog.COMMIT_PR.permissionLevel)
        assertFalse(AgentCatalog.COMMIT_PR.isReadOnly)
    }

    @Test
    fun `read-only roles cannot write`() {
        val registry = toolRegistry()
        val bridge = AgentToolBridge(registry)
        readOnlyRoles.forEach { role ->
            val definition = AgentCatalog.definition(role)
            assertTrue(definition.isReadOnly, role.name)
            assertEquals(PermissionLevel.READ_ONLY, definition.effectivePermission, role.name)
            assertFalse(WriteFileTool.NAME in definition.allowedTools, role.name)
            val allowed = bridge.filterAllowed(registry.names(), definition.effectivePermission)
            assertFalse("write_file" in allowed, role.name)
            assertFalse("run_command" in allowed, role.name)
            assertFalse("git_write" in allowed, role.name)
        }
    }

    @Test
    fun `commit pr cannot modify source files through coding tools`() {
        val registry = toolRegistry()
        val bridge = AgentToolBridge(registry)
        val definition = AgentCatalog.COMMIT_PR
        assertEquals(PermissionLevel.GIT_WRITE, definition.effectivePermission)
        assertFalse(WriteFileTool.NAME in definition.allowedTools)
        assertFalse(AgentProtocol.DELEGATE_TOOL in definition.allowedTools)

        val allowed = bridge.filterAllowed(
            names = registry.names() + definition.allowedTools + listOf(
                AgentProtocol.DELEGATE_TOOL,
                AgentProtocol.FINISH_TOOL,
            ),
            permission = definition.effectivePermission,
        )
        assertFalse("write_file" in allowed)
        assertFalse("run_command" in allowed)
        assertTrue("git_write" in allowed)
        assertTrue(AgentProtocol.FINISH_TOOL in allowed)
    }

    @Test
    fun `main remains the orchestrator role`() {
        val main = AgentCatalog.MAIN
        assertEquals(AgentRole.MAIN, main.role)
        assertTrue(AgentProtocol.DELEGATE_TOOL in main.allowedTools)
        assertTrue(AgentProtocol.FINISH_TOOL in main.allowedTools)
        AgentCatalog.all().filter { it.role != AgentRole.MAIN }.forEach { definition ->
            assertFalse(AgentProtocol.DELEGATE_TOOL in definition.allowedTools, definition.role.name)
        }
        assertTrue(main.maxSteps > AgentCatalog.DEFAULT_SUB_MAX_STEPS)
        newRoles.forEach { role ->
            assertEquals(AgentCatalog.DEFAULT_SUB_MAX_STEPS, AgentCatalog.definition(role).maxSteps)
        }
    }

    @Test
    fun `new roles can be created through the existing sub-agent runtime`() = runAgent {
        val registry = toolRegistry()
        val bridge = AgentToolBridge(registry)
        val factory = SpecializedAgentFactory(
            loop = AgentLoop(
                gateway = DefaultModelGateway(),
                toolRouter = DefaultToolRouter(registry),
                bridge = bridge,
            ),
            bridge = bridge,
            availableTools = { registry.names() },
        )
        val specialized = factory.createAll()
        assertEquals(11, specialized.all().size)
        assertNull(specialized.get(AgentRole.MAIN))

        newRoles.forEach { role ->
            val agent = factory.create(role)
            assertEquals(role, agent.definition.role)
            assertEquals(AgentCatalog.definition(role), agent.definition)
            assertNotNull(specialized.get(role))
        }

        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.PLANNER to mutableListOf(
                    response("", toolCall("write_file", "path" to "Auth.kt")),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Planned")),
                ),
            ),
        )
        val gateway = DefaultModelGateway().also { it.register(provider) }
        val plannerFactory = SpecializedAgentFactory(
            loop = AgentLoop(gateway, DefaultToolRouter(registry), bridge),
            bridge = bridge,
            availableTools = { registry.names() },
        )
        val writeFile = registry.find("write_file") as RecordingTool
        val result = plannerFactory.create(AgentRole.PLANNER).run(
            request = SubAgentRequest(
                role = AgentRole.PLANNER,
                task = "Plan auth",
                objective = "Produce a plan",
                parentSessionId = "parent",
                sessionId = "child",
            ),
            modelConfig = testConfig(),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )
        assertTrue(writeFile.invocations.isEmpty())
        assertTrue(result.errors.any { it.code == AgentErrorCode.PERMISSION_DENIED })
        assertEquals(AgentRole.PLANNER, result.role)
        assertEquals(AgentStatus.COMPLETED, result.status)

        val commitProvider = ScriptedModelProvider(
            mapOf(
                AgentRole.COMMIT_PR to mutableListOf(
                    response("", toolCall("write_file", "path" to "Auth.kt", "content" to "nope")),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Commit message")),
                ),
            ),
        )
        val commitGateway = DefaultModelGateway().also { it.register(commitProvider) }
        val commitFactory = SpecializedAgentFactory(
            loop = AgentLoop(commitGateway, DefaultToolRouter(registry), bridge),
            bridge = bridge,
            availableTools = { registry.names() },
        )
        val commitResult = commitFactory.create(AgentRole.COMMIT_PR).run(
            request = SubAgentRequest(
                role = AgentRole.COMMIT_PR,
                task = "Summarize the diff",
                objective = "Write a commit message",
                parentSessionId = "parent",
                sessionId = "commit-child",
            ),
            modelConfig = testConfig(),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )
        assertTrue(writeFile.invocations.isEmpty())
        assertTrue(commitResult.errors.any { it.code == AgentErrorCode.PERMISSION_DENIED })
        assertEquals(AgentRole.COMMIT_PR, commitResult.role)
    }

    @Test
    fun `new roles serialize and deserialize and unknown roles fall back to main`() {
        newRoles.forEach { role ->
            val encoded = ConversationCodec.encode(conversation(role = role, id = "sess-${role.name}"))
            val decoded = ConversationCodec.decode(encoded)
            assertNotNull(decoded)
            assertEquals(role, decoded.session.role)
            assertEquals("sess-${role.name}", decoded.session.id)
        }

        val legacy = ConversationCodec.encode(conversation(role = AgentRole.CODER, id = "sess-legacy"))
        val restored = ConversationCodec.decode(legacy)
        assertNotNull(restored)
        assertEquals(AgentRole.CODER, restored.session.role)

        val unknown = ConversationCodec.decode(
            """{"id":"sess-old","role":"LEGACY_ROLE","status":"IDLE","createdAtMillis":1,"updatedAtMillis":2,"task":{"id":"task","prompt":""},"messages":[]}""",
        )
        assertNotNull(unknown)
        assertEquals(AgentRole.MAIN, unknown.session.role)

        assertNull(AgentProtocol.parseRole("LEGACY_ROLE"))
        newRoles.forEach { role ->
            assertEquals(role, AgentProtocol.parseRole(role.name))
            assertEquals(role, AgentProtocol.parseRole(role.name.lowercase()))
        }
    }

    @Test
    fun `planner explorer reviewer and security reviewer stay inspect-only`() {
        listOf(
            AgentCatalog.PLANNER,
            AgentCatalog.EXPLORER,
            AgentCatalog.REVIEWER,
            AgentCatalog.SECURITY_REVIEWER,
        ).forEach { definition ->
            assertTrue(ListDirectoryTool.NAME in definition.allowedTools || definition.allowedTools.isEmpty())
            assertFalse(WriteFileTool.NAME in definition.allowedTools)
            assertEquals(PermissionLevel.READ_ONLY, definition.effectivePermission)
        }
        assertTrue(ListDirectoryTool.NAME in AgentCatalog.PLANNER.allowedTools)
        assertTrue(SearchFilesTool.NAME in AgentCatalog.PLANNER.allowedTools)
        assertTrue(ReadFileTool.NAME in AgentCatalog.PLANNER.allowedTools)
        assertTrue(ListDirectoryTool.NAME in AgentCatalog.SECURITY_REVIEWER.allowedTools)
        assertTrue(WriteFileTool.NAME in AgentCatalog.FAST_CODER.allowedTools)
        assertTrue(WriteFileTool.NAME in AgentCatalog.DOCS.allowedTools)
        // Commit/PR inspects what would be committed. Git and GitHub write tools do
        // not exist yet, so it holds read-only inspection tools and no mutating one.
        // An empty list was the old bug: it made the sub-agent factory hand this role
        // the entire tool registry instead.
        assertTrue(ListDirectoryTool.NAME in AgentCatalog.COMMIT_PR.allowedTools)
        assertTrue(ReadFileTool.NAME in AgentCatalog.COMMIT_PR.allowedTools)
        assertFalse(WriteFileTool.NAME in AgentCatalog.COMMIT_PR.allowedTools)
        assertFalse(AgentProtocol.DELEGATE_TOOL in AgentCatalog.COMMIT_PR.allowedTools)
        assertNull(AgentCatalog.PLANNER.modelPreference)
        assertNull(AgentCatalog.FAST_CODER.modelPreference)
        assertNull(AgentCatalog.SECURITY_REVIEWER.modelPreference)
        assertNull(AgentCatalog.DOCS.modelPreference)
        assertNull(AgentCatalog.COMMIT_PR.modelPreference)
    }

    private fun toolRegistry(): DefaultToolRegistry {
        val readFile = RecordingTool("read_file", setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM))
        val writeFile = RecordingTool("write_file", setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM))
        val runCommand = RecordingTool("run_command", setOf(ToolCapability.SHELL, ToolCapability.MUTATING))
        val webFetch = RecordingTool("web_fetch", setOf(ToolCapability.NETWORK, ToolCapability.READ_ONLY))
        val gitWrite = RecordingTool("git_write", setOf(ToolCapability.GIT, ToolCapability.MUTATING))
        return DefaultToolRegistry().also {
            it.register(readFile)
            it.register(writeFile)
            it.register(runCommand)
            it.register(webFetch)
            it.register(gitWrite)
        }
    }

    private fun conversation(role: AgentRole, id: String): AgentConversation = AgentConversation(
        session = AgentSession(
            id = id,
            role = role,
            status = AgentStatus.IDLE,
            task = AgentTask(id = "task-$id", prompt = "task"),
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
            workspaceId = "ws",
            title = role.name,
        ),
        messages = listOf(
            ConversationMessage(
                id = "$id-m0",
                sessionId = id,
                role = MessageRole.USER,
                content = MessageContent(text = "hello"),
                metadata = MessageMetadata(status = MessageStatus.COMPLETED, timestampMillis = 1L),
            ),
        ),
    )
}
