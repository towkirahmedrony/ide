package dev.forge.ide.agent

import dev.forge.ide.agent.catalog.AgentCatalog
import dev.forge.ide.agent.domain.AgentErrorCode
import dev.forge.ide.agent.domain.AgentEvent
import dev.forge.ide.agent.domain.AgentRole
import dev.forge.ide.agent.domain.AgentRunRequest
import dev.forge.ide.agent.domain.AgentStatus
import dev.forge.ide.agent.domain.CollectingEventSink
import dev.forge.ide.agent.domain.PermissionLevel
import dev.forge.ide.agent.domain.SubAgentRequest
import dev.forge.ide.agent.protocol.AgentProtocol
import dev.forge.ide.agent.runtime.AgentLoop
import dev.forge.ide.agent.runtime.AgentLoopRequest
import dev.forge.ide.agent.specialized.SpecializedAgentFactory
import dev.forge.ide.agent.tools.AgentToolBridge
import dev.forge.ide.model.DefaultModelGateway
import dev.forge.ide.model.ModelConfig
import dev.forge.ide.model.ModelProviderErrorCode
import dev.forge.ide.tools.DefaultToolRegistry
import dev.forge.ide.tools.DefaultToolRouter
import dev.forge.ide.tools.ToolCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
