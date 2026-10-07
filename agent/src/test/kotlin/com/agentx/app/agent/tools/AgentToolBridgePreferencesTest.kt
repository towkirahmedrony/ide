package com.agentx.app.agent.tools

import com.agentx.app.agent.domain.PermissionLevel
import com.agentx.app.agent.protocol.AgentProtocol
import com.agentx.app.tools.DefaultToolPreferences
import com.agentx.app.tools.DefaultToolRegistry
import com.agentx.app.tools.InMemoryToolPreferenceStore
import com.agentx.app.tools.Tool
import com.agentx.app.tools.ToolDefinition
import com.agentx.app.tools.ToolExecutionContext
import com.agentx.app.tools.ToolInput
import com.agentx.app.tools.ToolOutput
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exposure half of Settings → Tools: a tool turned off must not be named to
 * the model, resolved by a role policy, or sent as a schema — it may only be run
 * through the router, where it is refused.
 */
class AgentToolBridgePreferencesTest {

    private class ProbeTool(private val name: String) : Tool {
        override val definition = ToolDefinition(name = name, description = "Probe tool '$name'")

        override suspend fun execute(input: ToolInput, context: ToolExecutionContext): ToolOutput = ToolOutput()
    }

    private fun registry(vararg names: String): DefaultToolRegistry =
        DefaultToolRegistry().apply { names.forEach { register(ProbeTool(it)) } }

    @Test
    fun `a disabled tool is not offered and has no schema`() = runBlocking {
        val prefs = DefaultToolPreferences(InMemoryToolPreferenceStore())
        prefs.setEnabled("read_file", false)
        val bridge = AgentToolBridge(registry("read_file", "write_file"), prefs)

        val names = listOf("read_file", "write_file")

        assertTrue(bridge.definitionsFor(names).none { it.name == "read_file" })
        assertEquals(listOf("write_file"), bridge.filterAllowed(names, PermissionLevel.WORKSPACE_WRITE))
        assertEquals(listOf("write_file"), bridge.toModelSpecs(names).map { it.name })
    }

    @Test
    fun `an enabled tool is still offered`() = runBlocking {
        val prefs = DefaultToolPreferences(InMemoryToolPreferenceStore())
        val bridge = AgentToolBridge(registry("read_file"), prefs)

        assertEquals(listOf("read_file"), bridge.filterAllowed(listOf("read_file"), PermissionLevel.READ_ONLY))
        assertEquals(listOf("read_file"), bridge.toModelSpecs(listOf("read_file")).map { it.name })
    }

    @Test
    fun `the loop-handled protocol tools are unaffected by enablement`() = runBlocking {
        val prefs = DefaultToolPreferences(InMemoryToolPreferenceStore())
        prefs.setEnabled(AgentProtocol.DELEGATE_TOOL, false)
        val bridge = AgentToolBridge(registry(), prefs)

        assertEquals(
            listOf(AgentProtocol.DELEGATE_TOOL),
            bridge.filterAllowed(listOf(AgentProtocol.DELEGATE_TOOL), PermissionLevel.READ_ONLY),
        )
        assertEquals(listOf(AgentProtocol.DELEGATE_TOOL), bridge.toModelSpecs(listOf(AgentProtocol.DELEGATE_TOOL)).map { it.name })
    }
}
