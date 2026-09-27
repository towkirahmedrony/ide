package com.agentx.app.tools

import com.agentx.app.tools.mock.CurrentTimeTool
import com.agentx.app.tools.mock.EchoTool
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolSystemTest {

    private fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }

    private fun tool(
        name: String,
        permission: ToolPermissionDecision = ToolPermissionDecision.ALLOW,
        onExecute: suspend () -> ToolOutput = { ToolOutput() },
    ): Tool = object : Tool {
        override val definition = ToolDefinition(
            name = name,
            description = "Test tool '$name'",
            permission = permission,
        )

        override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput =
            onExecute()
    }

    private fun routerWith(vararg tools: Tool, policy: ToolPermissionPolicy = ToolPermissionPolicy.default()): ToolRouter {
        val registry = DefaultToolRegistry()
        tools.forEach(registry::register)
        return DefaultToolRouter(registry = registry, policy = policy)
    }

    // --- registry ----------------------------------------------------------

    @Test
    fun `registry registers and lists tools`() {
        val registry = DefaultToolRegistry()
        val echo = EchoTool()

        registry.register(echo)

        assertTrue(registry.contains("echo"))
        assertEquals(listOf("echo"), registry.names())
        assertEquals(echo, registry.find("echo"))
        assertEquals(listOf("echo"), registry.definitions().map { it.name })
    }

    @Test
    fun `registry rejects duplicate tool names`() {
        val registry = DefaultToolRegistry()
        registry.register(EchoTool())

        val error = assertFailsWith<ToolExecutionError> { registry.register(EchoTool()) }

        assertEquals(ToolErrorCode.DUPLICATE_TOOL, error.code)
    }

    @Test
    fun `registry unregisters tools`() {
        val registry = DefaultToolRegistry()
        registry.register(EchoTool())

        assertTrue(registry.unregister("echo"))
        assertNull(registry.find("echo"))
        assertFalse(registry.contains("echo"))
        assertFalse(registry.unregister("echo"))
    }

    @Test
    fun `registry exposes definitions with schema for the model layer`() {
        val schema = EchoTool().definition.toJsonSchema()

        assertEquals("echo", schema["name"]?.stringOrNull())
        assertEquals("object", schema["type"]?.stringOrNull())
        val required = assertIs<JsonValue.Arr>(schema["required"])
        assertTrue(required.items.any { it.stringOrNull() == "message" })
    }

    // --- router: resolution and validation ---------------------------------

    @Test
    fun `router resolves a registered tool by name`() {
        val router = routerWith(EchoTool())

        val result = runSuspend { router.invoke("echo", ToolInput(mapOf("message" to Json.of("hi")))) }

        assertIs<ToolResult.Success>(result)
    }

    @Test
    fun `router returns a structured error for an unknown tool`() {
        val router = routerWith(EchoTool())

        val result = runSuspend { router.invoke("missing") }

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals("missing", failure.toolName)
        assertEquals(ToolErrorCode.UNKNOWN_TOOL, failure.error.code)
    }

    @Test
    fun `router rejects a missing required argument`() {
        val router = routerWith(EchoTool())

        val result = runSuspend { router.invoke("echo") }

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
        assertTrue(failure.error.details.containsKey("errors"))
    }

    @Test
    fun `router rejects an argument of the wrong type`() {
        val router = routerWith(EchoTool())

        val result = runSuspend { router.invoke("echo", ToolInput(mapOf("message" to Json.of(42)))) }

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.error.code)
    }

    // --- router: execution -------------------------------------------------

    @Test
    fun `router executes a tool and returns a normalized success`() {
        val router = routerWith(EchoTool())

        val result = runSuspend { router.invoke("echo", ToolInput(mapOf("message" to Json.of("hello")))) }

        val success = assertIs<ToolResult.Success>(result)
        assertEquals("echo", success.toolName)
        assertEquals("hello", success.output.content["message"]?.stringOrNull())
        assertEquals(5.0, success.output.content["length"]?.numberOrNull())
        assertTrue(success.durationMillis >= 0)
    }

    @Test
    fun `router normalizes a thrown execution error`() {
        val failing = tool("boom") {
            throw ToolExecutionError(ToolErrorCode.EXECUTION_FAILED, "boom", "boom")
        }
        val router = routerWith(failing)

        val result = runSuspend { router.invoke("boom") }

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals("boom", failure.toolName)
        assertEquals(ToolErrorCode.EXECUTION_FAILED, failure.error.code)
        assertEquals("boom", failure.error.message)
    }

    @Test
    fun `router normalizes an unexpected throwable`() {
        val failing = tool("explode") { error("kaboom") }
        val router = routerWith(failing)

        val result = runSuspend { router.invoke("explode") }

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.EXECUTION_FAILED, failure.error.code)
        assertEquals("kaboom", failure.error.message)
    }

    @Test
    fun `current time tool returns structured output`() {
        val clock = Clock.fixed(Instant.parse("2020-01-02T03:04:05Z"), ZoneOffset.UTC)
        val router = routerWith(CurrentTimeTool(clock))

        val result = runSuspend { router.invoke("current_time") }

        val success = assertIs<ToolResult.Success>(result)
        assertEquals("2020-01-02T03:04:05Z", success.output.content["iso"]?.stringOrNull())
        assertEquals(1577934245000.0, success.output.content["epochMillis"]?.numberOrNull())
        assertEquals("Z", success.output.content["timeZone"]?.stringOrNull())
    }

    // --- permissions -------------------------------------------------------

    @Test
    fun `ALLOW permission runs the tool`() {
        var executed = false
        val allowed = tool("allowed", ToolPermissionDecision.ALLOW) {
            executed = true
            ToolOutput(content = mapOf("ran" to Json.of(true)))
        }
        val router = routerWith(allowed)

        val result = runSuspend { router.invoke("allowed") }

        assertIs<ToolResult.Success>(result)
        assertTrue(executed)
    }

    @Test
    fun `DENY permission blocks execution`() {
        var executed = false
        val blocked = tool("blocked", ToolPermissionDecision.DENY) {
            executed = true
            ToolOutput()
        }
        val router = routerWith(blocked)

        val result = runSuspend { router.invoke("blocked") }

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.PERMISSION_DENIED, failure.error.code)
        assertFalse(executed)
    }

    @Test
    fun `ASK permission pauses execution until approved`() {
        var executed = false
        val guarded = tool("guarded", ToolPermissionDecision.ASK) {
            executed = true
            ToolOutput(content = mapOf("ran" to Json.of(true)))
        }
        val router = routerWith(guarded)

        val pending = runSuspend { router.invoke("guarded") }
        val approval = assertIs<ToolResult.ApprovalRequired>(pending)
        assertEquals("guarded", approval.toolName)
        assertEquals("guarded", approval.request.toolName)
        assertTrue(approval.request.reason.isNotBlank())
        assertFalse(executed)

        val granted = runSuspend {
            router.invoke(
                "guarded",
                ToolInput(),
                ToolExecutionContext(approval = ToolApproval.granted()),
            )
        }
        assertIs<ToolResult.Success>(granted)
        assertTrue(executed)

        executed = false
        val denied = runSuspend {
            router.invoke(
                "guarded",
                ToolInput(),
                ToolExecutionContext(approval = ToolApproval.denied("nope")),
            )
        }
        val deniedFailure = assertIs<ToolResult.Failure>(denied)
        assertEquals(ToolErrorCode.PERMISSION_DENIED, deniedFailure.error.code)
        assertFalse(executed)
    }

    @Test
    fun `a custom policy can override the declared permission`() {
        // A tool that would ALLOW on its own is escalated to ASK by policy.
        val escalated = tool("escalate")
        val policy = ToolPermissionPolicy { ToolPermission.ask("policy says ask") }
        val router = routerWith(escalated, policy = policy)

        val result = runSuspend { router.invoke("escalate") }

        val approval = assertIs<ToolResult.ApprovalRequired>(result)
        assertEquals("policy says ask", approval.request.reason)
    }
}
