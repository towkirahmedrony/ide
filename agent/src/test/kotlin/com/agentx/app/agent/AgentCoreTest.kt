package com.agentx.app.agent

import com.agentx.app.agent.catalog.AgentCatalog
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.domain.SubAgentRequest
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.agent.runtime.AgentLoop
import com.agentx.app.agent.runtime.AgentLoopRequest
import com.agentx.app.agent.runtime.ResumedPermission
import com.agentx.app.agent.specialized.SpecializedAgentFactory
import com.agentx.app.agent.tools.AgentToolBridge
import com.agentx.app.model.DefaultModelGateway
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.ModelProviderErrorCode
import com.agentx.app.model.ModelRole
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.DefaultToolRouter
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolCapability
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolErrorCode
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolExecutionError
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolOutput
import com.agentx.app.tools.ToolPermissionDecision
import com.agentx.app.tools.ToolPermissionLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentCoreTest {

    private class Fixtures {
        val readFile = RecordingTool("read_file", setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM))
        val writeFile = RecordingTool("write_file", setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM))
        val runCommand = RecordingTool("run_command", setOf(ToolCapability.SHELL, ToolCapability.MUTATING))
        val webFetch = RecordingTool("web_fetch", setOf(ToolCapability.NETWORK, ToolCapability.READ_ONLY))
        val registry = DefaultToolRegistry().also {
            it.register(readFile)
            it.register(writeFile)
            it.register(runCommand)
            it.register(webFetch)
        }

        fun runtime(provider: ScriptedModelProvider, timeoutMillis: Long = 30_000L): AgentRuntime {
            val gateway = DefaultModelGateway()
            gateway.register(provider)
            return AgentModule.assemble(
                gateway = gateway,
                registry = registry,
                router = DefaultToolRouter(registry),
                timeoutMillis = timeoutMillis,
            )
        }
    }

    @Test
    fun `main delegates sequentially to coder then finishes`() = runAgent {
        val fx = Fixtures()
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response(
                        "",
                        toolCall(
                            AgentProtocol.DELEGATE_TOOL,
                            AgentProtocol.ARG_ROLE to "CODER",
                            AgentProtocol.ARG_TASK to "Patch login",
                            AgentProtocol.ARG_OBJECTIVE to "Fix the bug",
                        ),
                    ),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Login patched")),
                ),
                AgentRole.CODER to mutableListOf(
                    response(
                        "",
                        toolCall("write_file", "path" to "src/Login.kt", "content" to "fixed"),
                        toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Wrote Login.kt"),
                    ),
                ),
            ),
        )
        val sink = CollectingEventSink()
        val result = fx.runtime(provider).orchestrator.run(
            request = AgentRunRequest(prompt = "Fix login"),
            modelConfig = testConfig(),
            sink = sink,
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals("Login patched", result.summary)
        assertEquals(listOf("src/Login.kt"), result.filesChanged)
        assertEquals(1, fx.writeFile.invocations.size)
        assertTrue(sink.events.any { it is AgentEvent.SubAgentStarted && it.role == AgentRole.CODER })
        assertTrue(sink.events.any { it is AgentEvent.SubAgentCompleted && it.role == AgentRole.CODER })
        assertEquals(listOf(AgentRole.MAIN, AgentRole.CODER, AgentRole.MAIN), provider.completeCalls)
    }

    @Test
    fun `main sequences explorer then coder then reviewer`() = runAgent {
        val fx = Fixtures()
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    delegate(AgentRole.EXPLORER, "Map auth"),
                    delegate(AgentRole.CODER, "Edit auth"),
                    delegate(AgentRole.REVIEWER, "Review auth"),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Done")),
                ),
                AgentRole.EXPLORER to mutableListOf(
                    response(
                        "",
                        toolCall("read_file", "path" to "Auth.kt"),
                        toolCall(
                            AgentProtocol.FINISH_TOOL,
                            AgentProtocol.ARG_SUMMARY to "Mapped Auth.kt",
                            AgentProtocol.ARG_FILES_INSPECTED to "Auth.kt",
                        ),
                    ),
                ),
                AgentRole.CODER to mutableListOf(
                    response(
                        "",
                        toolCall("write_file", "path" to "Auth.kt"),
                        toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Edited Auth.kt"),
                    ),
                ),
                AgentRole.REVIEWER to mutableListOf(
                    response(
                        "",
                        toolCall("read_file", "path" to "Auth.kt"),
                        toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Looks good"),
                    ),
                ),
            ),
        )
        val result = fx.runtime(provider).orchestrator.run(
            request = AgentRunRequest(prompt = "Harden auth"),
            modelConfig = testConfig(),
            sink = CollectingEventSink(),
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals("Done", result.summary)
        assertTrue(result.filesInspected.contains("Auth.kt"))
        assertEquals(listOf("Auth.kt"), result.filesChanged)
        assertEquals(1, fx.writeFile.invocations.size)
        assertEquals(2, fx.readFile.invocations.size)
        assertEquals(
            listOf(
                AgentRole.MAIN,
                AgentRole.EXPLORER,
                AgentRole.MAIN,
                AgentRole.CODER,
                AgentRole.MAIN,
                AgentRole.REVIEWER,
                AgentRole.MAIN,
            ),
            provider.completeCalls,
        )
    }

    @Test
    fun `explorer cannot invoke mutating tools`() = runAgent {
        val fx = Fixtures()
        val bridge = AgentToolBridge(fx.registry)
        val allowed = bridge.filterAllowed(listOf("read_file", "write_file"), PermissionLevel.READ_ONLY)
        assertEquals(listOf("read_file"), allowed)

        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.EXPLORER to mutableListOf(
                    response("", toolCall("write_file", "path" to "Secret.kt")),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Stopped")),
                ),
            ),
        )
        val gateway = DefaultModelGateway().also { it.register(provider) }
        val factory = SpecializedAgentFactory(
            loop = AgentLoop(gateway, DefaultToolRouter(fx.registry), bridge),
            bridge = bridge,
            availableTools = { fx.registry.names() },
        )
        val result = factory.create(AgentRole.EXPLORER).run(
            request = SubAgentRequest(
                role = AgentRole.EXPLORER,
                task = "Inspect",
                objective = "Map",
                parentSessionId = "parent",
                sessionId = "child",
            ),
            modelConfig = testConfig(),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        assertTrue(fx.writeFile.invocations.isEmpty())
        assertTrue(result.errors.any { it.code == AgentErrorCode.PERMISSION_DENIED })
        assertEquals(AgentStatus.COMPLETED, result.status)
    }

    @Test
    fun `agent loop routes a tool call through the router and continues after the result`() = runAgent {
        val fx = Fixtures()
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("", toolCall("read_file", "path" to "Auth.kt")),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Inspected Auth.kt")),
                ),
            ),
        )
        val gateway = DefaultModelGateway().also { it.register(provider) }
        val sink = CollectingEventSink()
        val result = AgentLoop(
            gateway = gateway,
            toolRouter = DefaultToolRouter(fx.registry),
            bridge = AgentToolBridge(fx.registry),
        ).run(
            request = AgentLoopRequest(
                sessionId = "s",
                parentSessionId = null,
                definition = AgentCatalog.MAIN,
                allowedTools = listOf("read_file", AgentProtocol.FINISH_TOOL),
                permissionLevel = PermissionLevel.READ_ONLY,
                maxSteps = 4,
                userPrompt = "inspect auth",
                objective = null,
                scopedContext = "",
                workspaceId = "ws",
                modelConfig = testConfig(),
            ),
            sink = sink,
            onCancelled = { false },
        )
        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals("Inspected Auth.kt", result.summary)
        assertEquals(1, fx.readFile.invocations.size)
        assertEquals("Auth.kt", fx.readFile.invocations.single().string("path"))
        assertTrue(sink.events.any { it is AgentEvent.ToolCallStarted && it.toolName == "read_file" })
        assertTrue(sink.events.any { it is AgentEvent.ToolCallFinished && it.toolName == "read_file" && it.success })
        assertEquals(listOf(AgentRole.MAIN, AgentRole.MAIN), provider.completeCalls)
    }

    @Test
    fun `coder may write but cannot use network tools`() {
        val fx = Fixtures()
        val bridge = AgentToolBridge(fx.registry)
        val allowed = bridge.filterAllowed(fx.registry.names(), PermissionLevel.WORKSPACE_WRITE)
        assertTrue("write_file" in allowed)
        assertFalse("web_fetch" in allowed)
    }

    @Test
    fun `max steps stops the loop`() = runAgent {
        val fx = Fixtures()
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("", toolCall("read_file", "path" to "a.kt")),
                    response("", toolCall("read_file", "path" to "b.kt")),
                    response("", toolCall("read_file", "path" to "c.kt")),
                    response("", toolCall("read_file", "path" to "d.kt")),
                ),
            ),
        )
        val gateway = DefaultModelGateway().also { it.register(provider) }
        val limited = AgentLoop(
            gateway = gateway,
            toolRouter = DefaultToolRouter(fx.registry),
            bridge = AgentToolBridge(fx.registry),
        ).run(
            request = AgentLoopRequest(
                sessionId = "s",
                parentSessionId = null,
                definition = AgentCatalog.MAIN,
                allowedTools = listOf("read_file", AgentProtocol.FINISH_TOOL),
                permissionLevel = PermissionLevel.READ_ONLY,
                maxSteps = 2,
                userPrompt = "go",
                objective = null,
                scopedContext = "",
                workspaceId = null,
                modelConfig = testConfig(),
            ),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )
        assertEquals(AgentStatus.MAX_STEPS_REACHED, limited.status)
        assertTrue(limited.errors.any { it.code == AgentErrorCode.MAX_STEPS_EXCEEDED })
        assertEquals(2, fx.readFile.invocations.size)
    }

    @Test
    fun `cancellation stops before model calls`() = runAgent {
        val provider = ScriptedModelProvider(mapOf(AgentRole.MAIN to mutableListOf(response("should not run"))))
        val result = AgentLoop(
            gateway = DefaultModelGateway().also { it.register(provider) },
            toolRouter = DefaultToolRouter(DefaultToolRegistry()),
            bridge = AgentToolBridge(DefaultToolRegistry()),
        ).run(
            request = AgentLoopRequest(
                sessionId = "s",
                parentSessionId = null,
                definition = AgentCatalog.MAIN,
                allowedTools = listOf(AgentProtocol.FINISH_TOOL),
                permissionLevel = PermissionLevel.READ_ONLY,
                maxSteps = 4,
                userPrompt = "go",
                objective = null,
                scopedContext = "",
                workspaceId = null,
                modelConfig = testConfig(),
            ),
            sink = CollectingEventSink(),
            onCancelled = { true },
        )
        assertEquals(AgentStatus.CANCELLED, result.status)
        assertTrue(result.errors.any { it.code == AgentErrorCode.CANCELLED })
        assertTrue(provider.completeCalls.isEmpty())
    }

    @Test
    fun `model gateway errors become agent failures`() = runAgent {
        val sink = CollectingEventSink()
        val result = AgentLoop(
            gateway = throwingGateway(),
            toolRouter = DefaultToolRouter(DefaultToolRegistry()),
            bridge = AgentToolBridge(DefaultToolRegistry()),
        ).run(
            request = AgentLoopRequest(
                sessionId = "s",
                parentSessionId = null,
                definition = AgentCatalog.MAIN,
                allowedTools = listOf(AgentProtocol.FINISH_TOOL),
                permissionLevel = PermissionLevel.READ_ONLY,
                maxSteps = 3,
                userPrompt = "go",
                objective = null,
                scopedContext = "",
                workspaceId = null,
                modelConfig = testConfig(),
            ),
            sink = sink,
            onCancelled = { false },
        )
        assertEquals(AgentStatus.FAILED, result.status)
        assertTrue(result.errors.any { it.code == AgentErrorCode.MODEL_FAILURE })
        assertTrue(sink.events.any { it is AgentEvent.Failed })
    }

    @Test
    fun `model timeout maps to agent timeout`() = runAgent {
        val result = runFailingLoop(providerError(ModelProviderErrorCode.TIMEOUT, "timed out"))
        val error = result.errors.single()
        assertEquals(AgentStatus.FAILED, result.status)
        assertEquals(AgentErrorCode.TIMEOUT, error.code)
        assertEquals("timed out", error.message)
        assertEquals(ModelProviderErrorCode.TIMEOUT.name, error.details["providerError"])
    }

    @Test
    fun `invalid model response maps to malformed response`() = runAgent {
        val result = runFailingLoop(providerError(ModelProviderErrorCode.INVALID_RESPONSE, "bad json"))
        val error = result.errors.single()
        assertEquals(AgentErrorCode.MALFORMED_RESPONSE, error.code)
        assertEquals("bad json", error.message)
        assertEquals(ModelProviderErrorCode.INVALID_RESPONSE.name, error.details["providerError"])
    }

    @Test
    fun `connection refused maps to model failure with provider details`() = runAgent {
        val result = runFailingLoop(providerError(ModelProviderErrorCode.CONNECTION_FAILED, "refused"))
        val error = result.errors.single()
        assertEquals(AgentErrorCode.MODEL_FAILURE, error.code)
        assertEquals("refused", error.message)
        assertEquals(ModelProviderErrorCode.CONNECTION_FAILED.name, error.details["providerError"])
    }

    private suspend fun runFailingLoop(error: Throwable) = AgentLoop(
        gateway = throwingGateway(error),
        toolRouter = DefaultToolRouter(DefaultToolRegistry()),
        bridge = AgentToolBridge(DefaultToolRegistry()),
    ).run(
        request = AgentLoopRequest(
            sessionId = "s",
            parentSessionId = null,
            definition = AgentCatalog.MAIN,
            allowedTools = listOf(AgentProtocol.FINISH_TOOL),
            permissionLevel = PermissionLevel.READ_ONLY,
            maxSteps = 3,
            userPrompt = "go",
            objective = null,
            scopedContext = "",
            workspaceId = null,
            modelConfig = testConfig(),
        ),
        sink = CollectingEventSink(),
        onCancelled = { false },
    )

    @Test
    fun `invalid model config is rejected by the orchestrator`() = runAgent {
        val fx = Fixtures()
        val provider = ScriptedModelProvider(mapOf(AgentRole.MAIN to mutableListOf(response("nope"))))
        val result = fx.runtime(provider).orchestrator.run(
            request = AgentRunRequest(prompt = "go"),
            modelConfig = ModelConfig(providerId = "", baseUrl = "", model = ""),
            sink = CollectingEventSink(),
        )
        assertEquals(AgentStatus.FAILED, result.status)
        assertTrue(result.errors.any { it.code == AgentErrorCode.NOT_CONFIGURED })
        assertTrue(provider.completeCalls.isEmpty())
    }

    @Test
    fun `subagents never receive unrestricted tools`() {
        val fx = Fixtures()
        val bridge = AgentToolBridge(fx.registry)

        val explorer = bridge.filterAllowed(fx.registry.names(), AgentCatalog.EXPLORER.effectivePermission)
        val reviewer = bridge.filterAllowed(fx.registry.names(), AgentCatalog.REVIEWER.effectivePermission)
        val coder = bridge.filterAllowed(fx.registry.names(), AgentCatalog.CODER.effectivePermission)

        assertEquals(listOf("read_file"), explorer)
        assertEquals(listOf("read_file"), reviewer)
        assertTrue("write_file" in coder)
        assertFalse("run_command" in coder)
        assertFalse("run_command" in explorer)
    }

    private fun delegate(role: AgentRole, task: String) = response(
        "",
        toolCall(
            AgentProtocol.DELEGATE_TOOL,
            AgentProtocol.ARG_ROLE to role.name,
            AgentProtocol.ARG_TASK to task,
            AgentProtocol.ARG_OBJECTIVE to task,
        ),
    )

    // --- tool execution loop ----------------------------------------------

    private fun agentRequest(
        allowedTools: List<String>,
        permissionLevel: PermissionLevel = PermissionLevel.READ_ONLY,
        maxSteps: Int = 4,
        workspaceId: String? = null,
    ): AgentLoopRequest = AgentLoopRequest(
        sessionId = "s",
        parentSessionId = null,
        definition = AgentCatalog.MAIN,
        allowedTools = allowedTools,
        permissionLevel = permissionLevel,
        maxSteps = maxSteps,
        userPrompt = "go",
        objective = null,
        scopedContext = "",
        workspaceId = workspaceId,
        modelConfig = testConfig(),
    )

    private fun loopOver(registry: DefaultToolRegistry, provider: ScriptedModelProvider): AgentLoop {
        val gateway = DefaultModelGateway().also { it.register(provider) }
        return AgentLoop(gateway, DefaultToolRouter(registry), AgentToolBridge(registry))
    }

    @Test
    fun `tool result is added to the model context before the next call`() = runAgent {
        val fx = Fixtures()
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("", toolCall("read_file", "path" to "Auth.kt", id = "c1")),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "ok")),
                ),
            ),
        )
        loopOver(fx.registry, provider).run(
            request = agentRequest(listOf("read_file", AgentProtocol.FINISH_TOOL)),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        assertEquals(2, provider.requests.size)
        val continuation = provider.requests[1].messages
        assertTrue(continuation.any { it.role == ModelRole.ASSISTANT && it.toolCalls.any { call -> call.id == "c1" } })
        val toolMessage = continuation.single { it.role == ModelRole.TOOL }
        assertEquals("c1", toolMessage.toolCallId)
        assertTrue(toolMessage.content.contains("Auth.kt"))
    }

    @Test
    fun `multiple tool calls in one response all execute and continue`() = runAgent {
        val fx = Fixtures()
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response(
                        "",
                        toolCall("read_file", "path" to "a.kt", id = "c1"),
                        toolCall("read_file", "path" to "b.kt", id = "c2"),
                    ),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "done")),
                ),
            ),
        )
        val result = loopOver(fx.registry, provider).run(
            request = agentRequest(listOf("read_file", AgentProtocol.FINISH_TOOL)),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals(2, fx.readFile.invocations.size)
        assertEquals(listOf("a.kt", "b.kt"), fx.readFile.invocations.map { it.string("path") })
        assertEquals(2, provider.requests[1].messages.count { it.role == ModelRole.TOOL })
    }

    @Test
    fun `unknown tool returns a structured error the model can recover from`() = runAgent {
        val fx = Fixtures()
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("", toolCall("ghost_tool", id = "g1")),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "recovered")),
                ),
            ),
        )
        val result = loopOver(fx.registry, provider).run(
            request = agentRequest(listOf("ghost_tool", AgentProtocol.FINISH_TOOL)),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertTrue(result.errors.any { it.code == AgentErrorCode.TOOL_UNKNOWN })
        val toolMessage = provider.requests[1].messages.single { it.role == ModelRole.TOOL }
        assertTrue(toolMessage.content.startsWith("ERROR:"))
    }

    @Test
    fun `tool failure is reported to the model and the loop recovers`() = runAgent {
        val broken = object : Tool {
            override val definition = ToolDefinition(
                name = "broken",
                description = "Always fails",
                capabilities = setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM),
            )

            override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput =
                throw ToolExecutionError(ToolErrorCode.EXECUTION_FAILED, "disk melted", "broken")
        }
        val registry = DefaultToolRegistry().also { it.register(broken) }
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("", toolCall("broken", id = "b1")),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "recovered")),
                ),
            ),
        )
        val result = loopOver(registry, provider).run(
            request = agentRequest(listOf("broken", AgentProtocol.FINISH_TOOL)),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertTrue(result.errors.any { it.code == AgentErrorCode.TOOL_FAILURE })
        assertTrue(provider.requests[1].messages.single { it.role == ModelRole.TOOL }.content.contains("disk melted"))
    }

    @Test
    fun `tool lifecycle events are emitted in order`() = runAgent {
        val fx = Fixtures()
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("", toolCall("read_file", "path" to "Auth.kt", id = "c1")),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "ok")),
                ),
            ),
        )
        val sink = CollectingEventSink()
        loopOver(fx.registry, provider).run(
            request = agentRequest(listOf("read_file", AgentProtocol.FINISH_TOOL)),
            sink = sink,
            onCancelled = { false },
        )

        val requested = assertNotNull(sink.events.filterIsInstance<AgentEvent.ToolRequested>().firstOrNull())
        assertEquals("read_file", requested.toolName)
        assertEquals("c1", requested.toolCallId)
        assertTrue(sink.events.any { it is AgentEvent.ToolCallStarted && it.toolName == "read_file" })
        assertTrue(sink.events.any { it is AgentEvent.ToolProgress && it.toolName == "read_file" })
        assertTrue(sink.events.any { it is AgentEvent.ToolCallFinished && it.toolName == "read_file" && it.success })
    }

    @Test
    fun `permission required pauses the run and resumes when approved`() = runAgent {
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
        val loop = loopOver(registry, provider)
        val allowed = listOf("guarded_write", AgentProtocol.FINISH_TOOL)
        val sink = CollectingEventSink()

        val paused = loop.run(
            request = agentRequest(allowed, PermissionLevel.WORKSPACE_WRITE),
            sink = sink,
            onCancelled = { false },
        )

        assertEquals(AgentStatus.WAITING_FOR_PERMISSION, paused.status)
        val pending = assertNotNull(paused.pendingPermission)
        assertEquals("guarded_write", pending.toolName)
        assertEquals("c1", pending.toolCallId)
        assertTrue(pending.requiredPermissions.contains("WORKSPACE_WRITE"))
        assertTrue(guarded.invocations.isEmpty(), "the tool must not run before approval")
        assertTrue(sink.events.any { it is AgentEvent.PermissionRequested })

        val resumed = loop.run(
            request = agentRequest(allowed, PermissionLevel.WORKSPACE_WRITE).copy(
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
        assertEquals(1, guarded.invocations.size)
        assertEquals("a.kt", guarded.invocations.single().string("path"))
    }

    @Test
    fun `denied permission never runs the tool`() = runAgent {
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
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "stopped")),
                ),
            ),
        )
        val loop = loopOver(registry, provider)
        val allowed = listOf("guarded_write", AgentProtocol.FINISH_TOOL)
        val paused = loop.run(
            request = agentRequest(allowed, PermissionLevel.WORKSPACE_WRITE),
            sink = CollectingEventSink(),
            onCancelled = { false },
        )
        val pending = assertNotNull(paused.pendingPermission)

        val resumed = loop.run(
            request = agentRequest(allowed, PermissionLevel.WORKSPACE_WRITE).copy(
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

        assertTrue(guarded.invocations.isEmpty(), "a denied tool must never execute")
        assertTrue(resumed.errors.any { it.code == AgentErrorCode.PERMISSION_DENIED })
        assertEquals(AgentStatus.COMPLETED, resumed.status)
    }

    // --- Main Agent real workflow -----------------------------------------

    @Test
    fun `main agent inspects the workspace with its own tools`() = runAgent {
        val search = RecordingTool("search_files", setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM))
        val read = RecordingTool("read_file", setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM))
        val registry = DefaultToolRegistry().also {
            it.register(search)
            it.register(read)
        }
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("", toolCall("search_files", "query" to "auth", id = "s1")),
                    response("", toolCall("read_file", "path" to "Auth.kt", id = "r1")),
                    response(
                        "",
                        toolCall(
                            AgentProtocol.FINISH_TOOL,
                            AgentProtocol.ARG_SUMMARY to "Auth lives in Auth.kt",
                            AgentProtocol.ARG_FILES_INSPECTED to "Auth.kt",
                        ),
                    ),
                ),
            ),
        )
        val runtime = AgentModule.assemble(DefaultModelGateway().also { it.register(provider) }, registry, DefaultToolRouter(registry))

        val result = runtime.orchestrator.run(
            request = AgentRunRequest(prompt = "Find where authentication is implemented."),
            modelConfig = testConfig(),
            sink = CollectingEventSink(),
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals(1, search.invocations.size)
        assertEquals(1, read.invocations.size)
        assertTrue(result.filesInspected.contains("Auth.kt"))
    }

    @Test
    fun `main agent changes a file through the tool router`() = runAgent {
        val read = RecordingTool("read_file", setOf(ToolCapability.READ_ONLY, ToolCapability.FILESYSTEM))
        val write = RecordingTool(
            name = "write_file",
            capabilities = setOf(ToolCapability.MUTATING, ToolCapability.FILESYSTEM),
            required = setOf(ToolPermissionLevel.WORKSPACE_WRITE),
        )
        val registry = DefaultToolRegistry().also {
            it.register(read)
            it.register(write)
        }
        val provider = ScriptedModelProvider(
            mapOf(
                AgentRole.MAIN to mutableListOf(
                    response("", toolCall("read_file", "path" to "Auth.kt", id = "r1")),
                    response("", toolCall("write_file", "path" to "Auth.kt", "content" to "fixed", id = "w1")),
                    response("", toolCall(AgentProtocol.FINISH_TOOL, AgentProtocol.ARG_SUMMARY to "Patched Auth.kt")),
                ),
            ),
        )
        val runtime = AgentModule.assemble(DefaultModelGateway().also { it.register(provider) }, registry, DefaultToolRouter(registry))

        val result = runtime.orchestrator.run(
            request = AgentRunRequest(prompt = "Change Auth.kt"),
            modelConfig = testConfig(),
            sink = CollectingEventSink(),
        )

        assertEquals(AgentStatus.COMPLETED, result.status)
        assertEquals(1, write.invocations.size)
        assertTrue(result.filesChanged.contains("Auth.kt"))
    }
}
