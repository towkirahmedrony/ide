package com.agentx.app.tools

import com.agentx.app.core.valueOrNull
import com.agentx.app.tools.filesystem.ListDirectoryTool
import com.agentx.app.tools.filesystem.ReadFileTool
import com.agentx.app.tools.filesystem.SearchFilesTool
import com.agentx.app.tools.filesystem.WriteFileTool
import com.agentx.app.workspace.DefaultWorkspaceManager
import com.agentx.app.workspace.memory.InMemoryWorkspaceBackend
import com.agentx.app.workspace.memory.InMemoryWorkspaceFileSystem
import com.agentx.app.workspace.memory.InMemoryWorkspaceMetadataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolSystemFoundationTest {

    private fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }

    private fun tool(
        name: String,
        permission: ToolPermissionDecision = ToolPermissionDecision.ALLOW,
        required: Set<ToolPermissionLevel> = emptySet(),
        onExecute: suspend () -> ToolOutput = { ToolOutput() },
    ): Tool = object : Tool {
        override val definition = ToolDefinition(
            name = name,
            description = "Test tool '$name'",
            permission = permission,
            requiredPermissions = required,
        )

        override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput = onExecute()
    }

    private fun routerWith(
        vararg tools: Tool,
        policy: ToolPermissionPolicy = ToolPermissionPolicy.default(),
        executor: ToolExecutor = DefaultToolExecutor(),
    ): Pair<DefaultToolRegistry, ToolRouter> {
        val registry = DefaultToolRegistry()
        tools.forEach(registry::register)
        return registry to DefaultToolRouter(registry = registry, policy = policy, executor = executor)
    }

    private fun grants(vararg levels: ToolPermissionLevel, timeoutMillis: Long? = null): ToolExecutionContext =
        ToolExecutionContext(grantedPermissions = levels.toSet(), timeoutMillis = timeoutMillis)

    @Test
    fun `registry registers finds and lists tools`() {
        val registry = DefaultToolRegistry()
        val echo = tool("echo")
        registry.register(echo)
        assertTrue(registry.contains("echo"))
        assertEquals(echo, registry.find(ToolId("echo")))
        assertEquals(listOf("echo"), registry.names())
        assertEquals(listOf("echo"), registry.definitions().map { it.name })
    }

    @Test
    fun `registry rejects duplicate tool ids`() {
        val registry = DefaultToolRegistry()
        registry.register(tool("dup"))
        val error = assertFailsWith<ToolExecutionError> { registry.register(tool("dup")) }
        assertEquals(ToolErrorCode.DUPLICATE_TOOL, error.code)
    }

    @Test
    fun `registry lookup returns null for an unknown id`() {
        val registry = DefaultToolRegistry()
        assertNull(registry.find("missing"))
        assertFalse(registry.contains("missing"))
    }

    @Test
    fun `registry unregisters tools`() {
        val registry = DefaultToolRegistry()
        registry.register(tool("gone"))
        assertTrue(registry.unregister("gone"))
        assertNull(registry.find("gone"))
        assertFalse(registry.unregister("gone"))
    }

    @Test
    fun `router returns a structured error for an invalid tool id`() {
        val (_, router) = routerWith(tool("echo"))
        val result = runSuspend { router.invoke("nope") }
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.UNKNOWN_TOOL, failure.error.code)
        assertEquals(ToolExecutionStatus.FAILED, result.status)
    }

    @Test
    fun `router rejects invalid input`() {
        val echo = object : Tool {
            override val definition = ToolDefinition(
                name = "typed",
                description = "Requires a string",
                inputSchema = ToolInputSchema(
                    parameters = listOf(
                        ToolParameter("message", ToolParameterType.STRING, required = true),
                    ),
                ),
            )

            override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput = ToolOutput()
        }
        val (_, router) = routerWith(echo)
        val missing = runSuspend { router.invoke("typed") }
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, assertIs<ToolResult.Failure>(missing).error.code)
        val wrong = runSuspend { router.invoke("typed", ToolInput(mapOf("message" to Json.of(1)))) }
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, assertIs<ToolResult.Failure>(wrong).error.code)
    }

    @Test
    fun `missing required permission denies without executing`() {
        var executed = false
        val guarded = tool("write", required = setOf(ToolPermissionLevel.WORKSPACE_WRITE)) {
            executed = true
            ToolOutput()
        }
        val (_, router) = routerWith(guarded)
        val denied = runSuspend { router.invoke("write", context = grants(ToolPermissionLevel.READ_ONLY)) }
        val failure = assertIs<ToolResult.Failure>(denied)
        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
        assertEquals(ToolExecutionStatus.PERMISSION_REQUIRED, denied.status)
        assertFalse(executed)
    }

    @Test
    fun `granted permission allows execution`() {
        val allowed = tool("read", required = setOf(ToolPermissionLevel.READ_ONLY)) {
            ToolOutput(content = mapOf("ok" to Json.of(true)))
        }
        val (_, router) = routerWith(allowed)
        val result = runSuspend { router.invoke("read", context = grants(ToolPermissionLevel.READ_ONLY)) }
        val success = assertIs<ToolResult.Success>(result)
        assertEquals(true, success.output.content["ok"]?.booleanOrNull())
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.status)
    }

    @Test
    fun `successful execution returns structured output`() {
        val echo = tool("ok") { ToolOutput(content = mapOf("value" to Json.of("hi")), displayText = "hi") }
        val (_, router) = routerWith(echo)
        val result = runSuspend { router.invoke(ToolCall(id = "c1", toolId = ToolId("ok"))) }
        val success = assertIs<ToolResult.Success>(result)
        assertEquals("ok", success.toolName)
        assertEquals("hi", success.output.displayText)
    }

    @Test
    fun `execution failure is captured as data`() {
        val boom = tool("boom") { throw ToolExecutionError(ToolErrorCode.EXECUTION_FAILED, "nope", "boom") }
        val (_, router) = routerWith(boom)
        val result = runSuspend { router.invoke("boom") }
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.EXECUTION_FAILED, failure.error.code)
        assertEquals("nope", failure.error.message)
        assertEquals(ToolExecutionStatus.FAILED, result.status)
    }

    @Test
    fun `timeout returns a structured timeout result`() {
        val slow = tool("slow") {
            delay(5_000)
            ToolOutput()
        }
        val (_, router) = routerWith(slow)
        val result = runSuspend { router.invoke("slow", context = grants(timeoutMillis = 40)) }
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.TIMEOUT, failure.error.code)
        assertEquals(ToolExecutionStatus.TIMED_OUT, result.status)
    }

    @Test
    fun `cancellation is propagated to the caller`() = runBlocking {
        val slow = tool("cancel_me") {
            delay(10_000)
            ToolOutput()
        }
        val (_, router) = routerWith(slow)
        val deferred = async { router.invoke("cancel_me") }
        delay(20)
        deferred.cancel()
        val thrown = runCatching { deferred.await() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `router never crashes the caller when a tool throws`() {
        val exploding = tool("explode") { error("kaboom") }
        val (_, router) = routerWith(exploding)
        val result = runSuspend { router.invoke("explode") }
        assertIs<ToolResult.Failure>(result)
        assertEquals("kaboom", result.error.message)
    }

    @Test
    fun `secrets are redacted from tool output`() {
        val leaky = tool("leak") {
            ToolOutput(
                content = mapOf(
                    "api_key" to Json.of("sk-secret"),
                    "note" to Json.of("token=abcd"),
                ),
                displayText = "OPENAI_API_KEY=sk-secret",
            )
        }
        val (_, router) = routerWith(leaky)
        val result = runSuspend { router.invoke("leak") }
        val success = assertIs<ToolResult.Success>(result)
        assertEquals(SecretRedactor.REDACTED, success.output.content["api_key"]?.stringOrNull())
        assertTrue(success.output.displayText.orEmpty().contains(SecretRedactor.REDACTED))
        assertFalse(success.output.displayText.orEmpty().contains("sk-secret"))
    }

    @Test
    fun `filesystem tools list read write and search through the workspace runtime`() {
        val fs = InMemoryWorkspaceFileSystem(
            seedFiles = mapOf(
                "README.md" to "hello agent",
                "src/Main.kt" to "fun main() {}",
            ),
        )
        val resolver = WorkspaceFileSystemResolver { fs }
        val (_, router) = routerWith(
            ListDirectoryTool(resolver),
            ReadFileTool(resolver),
            WriteFileTool(resolver),
            SearchFilesTool(resolver),
        )
        val readCtx = grants(ToolPermissionLevel.READ_ONLY)
        val writeCtx = grants(ToolPermissionLevel.READ_ONLY, ToolPermissionLevel.WORKSPACE_WRITE)

        val listed = runSuspend { router.invoke("list_directory", context = readCtx) }
        val listedOk = assertIs<ToolResult.Success>(listed)
        assertTrue((listedOk.output.content["count"]?.numberOrNull() ?: 0.0) >= 2.0)

        val read = runSuspend {
            router.invoke("read_file", ToolInput(mapOf("path" to Json.of("README.md"))), readCtx)
        }
        assertEquals("hello agent", assertIs<ToolResult.Success>(read).output.content["content"]?.stringOrNull())

        val writeDenied = runSuspend {
            router.invoke(
                "write_file",
                ToolInput(mapOf("path" to Json.of("new.txt"), "content" to Json.of("x"))),
                readCtx,
            )
        }
        assertEquals(ToolErrorCode.PERMISSION_DENIED, assertIs<ToolResult.Failure>(writeDenied).error.code)

        // Writing is medium risk: it parks for approval before it ever runs.
        val writeInput = ToolInput(mapOf("path" to Json.of("docs/note.txt"), "content" to Json.of("saved")))
        val pending = runSuspend { router.invoke("write_file", writeInput, writeCtx) }
        assertEquals("write_file", assertIs<ToolResult.ApprovalRequired>(pending).toolName)

        val written = runSuspend {
            router.invoke(
                "write_file",
                writeInput,
                writeCtx.copy(approval = ToolApproval.granted("approved in test")),
            )
        }
        assertIs<ToolResult.Success>(written)
        val reread = runSuspend {
            router.invoke("read_file", ToolInput(mapOf("path" to Json.of("docs/note.txt"))), readCtx)
        }
        assertEquals("saved", assertIs<ToolResult.Success>(reread).output.content["content"]?.stringOrNull())

        val search = runSuspend {
            router.invoke("search_files", ToolInput(mapOf("query" to Json.of("hello"))), readCtx)
        }
        val searchOk = assertIs<ToolResult.Success>(search)
        assertTrue((searchOk.output.content["count"]?.numberOrNull() ?: 0.0) >= 1.0)
    }

    @Test
    fun `filesystem tools reject path traversal`() {
        val fs = InMemoryWorkspaceFileSystem(seedFiles = mapOf("a.txt" to "x"))
        val resolver = WorkspaceFileSystemResolver { fs }
        val (_, router) = routerWith(ReadFileTool(resolver))
        val result = runSuspend {
            router.invoke(
                "read_file",
                ToolInput(mapOf("path" to Json.of("../secret"))),
                grants(ToolPermissionLevel.READ_ONLY),
            )
        }
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
    }

    @Test
    fun `workspace resolver uses the live manager session and rejects a mismatch`() {
        val manager = DefaultWorkspaceManager(
            backend = InMemoryWorkspaceBackend(
                seedFiles = mapOf("README.md" to "# MyProject"),
            ),
            store = InMemoryWorkspaceMetadataStore(),
        )
        val opened = runSuspend { manager.open("MyProject") }.valueOrNull()
        assertNotNull(opened)
        val resolver = WorkspaceManagerFileSystemResolver(manager)
        val matched = resolver.resolve(
            ToolExecutionContext(workspaceId = opened.workspace.id.value),
        )
        assertNotNull(matched)
        assertEquals("# MyProject", runSuspend { matched.readFile("README.md") }.valueOrNull())

        assertNull(resolver.resolve(ToolExecutionContext(workspaceId = "other-id")))

        val blank = resolver.resolve(ToolExecutionContext(workspaceId = null))
        assertNotNull(blank)
    }

    @Test
    fun `filesystem tools fail closed when no workspace is open`() {
        val manager = DefaultWorkspaceManager(
            backend = InMemoryWorkspaceBackend(),
            store = InMemoryWorkspaceMetadataStore(),
        )
        val resolver = WorkspaceManagerFileSystemResolver(manager)
        val (_, router) = routerWith(ListDirectoryTool(resolver), ReadFileTool(resolver))
        val listed = runSuspend {
            router.invoke("list_directory", context = grants(ToolPermissionLevel.READ_ONLY))
        }
        assertEquals(ToolErrorCode.WORKSPACE_UNAVAILABLE, assertIs<ToolResult.Failure>(listed).error.code)
    }
}
