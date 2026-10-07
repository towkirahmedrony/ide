package com.agentx.app.tools

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Settings → Tools writes [DefaultToolPreferences]; the Tool Router reads it.
 * A tool the user turned off must never reach the executor, and turning it back
 * on must restore it — with the choice persisted through the store.
 */
class ToolPreferencesTest {

    private class ProbeTool(private val name: String, val onExecute: () -> Unit = {}) : Tool {
        override val definition = ToolDefinition(
            name = name,
            title = name,
            description = "Probe tool '$name'",
            permission = ToolPermissionDecision.ALLOW,
        )

        override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput {
            onExecute()
            return ToolOutput(content = mapOf("ok" to Json.of(true)))
        }
    }

    private fun router(preferences: ToolPreferences, vararg tools: Tool): ToolRouter {
        val registry = DefaultToolRegistry()
        tools.forEach(registry::register)
        return DefaultToolRouter(registry = registry, preferences = preferences)
    }

    @Test
    fun `a disabled tool is refused with its own code and never executes`() = runBlocking {
        var executed = false
        val prefs = DefaultToolPreferences(InMemoryToolPreferenceStore())
        prefs.setEnabled("echo", false)

        val result = router(prefs, ProbeTool("echo") { executed = true }).invoke("echo")

        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals(ToolErrorCode.TOOL_DISABLED, failure.error.code)
        assertFalse(executed, "a disabled tool must not reach the executor")
    }

    @Test
    fun `re-enabling a tool lets it run again`() = runBlocking {
        var executed = false
        val prefs = DefaultToolPreferences(InMemoryToolPreferenceStore())
        prefs.setEnabled("echo", false)

        val router = router(prefs, ProbeTool("echo") { executed = true })
        assertIs<ToolResult.Failure>(router.invoke("echo"))

        prefs.setEnabled("echo", true)
        val success = assertIs<ToolResult.Success>(router.invoke("echo"))

        assertTrue(executed)
        assertEquals(true, success.output.content.booleanOrNull("ok"))
    }

    @Test
    fun `the default preference set runs every tool`() = runBlocking {
        val result = router(AllowAllToolPreferences, ProbeTool("echo")).invoke("echo")
        assertIs<ToolResult.Success>(result)
    }

    @Test
    fun `the disabled set persists through the store and reloads`() = runBlocking {
        val store = InMemoryToolPreferenceStore()
        val prefs = DefaultToolPreferences(store)
        prefs.setEnabled("write_file", false)
        prefs.setEnabled("run_command", false)

        assertEquals(setOf("write_file", "run_command"), store.load())

        // A fresh preferences instance over the same store sees the saved choice.
        val restored = DefaultToolPreferences(store)
        restored.load()
        assertFalse(restored.isEnabled("write_file"))
        assertFalse(restored.isEnabled("run_command"))
        assertTrue(restored.isEnabled("read_file"))
    }

    @Test
    fun `reset turns every tool back on and clears the store`() = runBlocking {
        val store = InMemoryToolPreferenceStore()
        val prefs = DefaultToolPreferences(store)
        prefs.setEnabled("echo", false)

        prefs.reset()

        assertTrue(prefs.isEnabled("echo"))
        assertTrue(store.load().isEmpty())
    }
}
