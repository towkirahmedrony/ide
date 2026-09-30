package com.agentx.app.agent.prompt

import com.agentx.app.agent.domain.AgentRole
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptManagerTest {

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    @Test
    fun `uses the built-in default when no override exists`() = run {
        val manager = PromptManager()
        val resolved = manager.resolve(AgentRole.MAIN)
        assertEquals(AgentPromptSource.DEFAULT, resolved.source)
        assertEquals(DefaultAgentPrompts.MAIN, resolved.text)
    }

    @Test
    fun `custom prompt wins over the default`() = run {
        val manager = PromptManager()
        manager.save(AgentRole.CODER, "Custom coder prompt")
        val resolved = manager.resolve(AgentRole.CODER)
        assertEquals(AgentPromptSource.CUSTOM, resolved.source)
        assertEquals("Custom coder prompt", resolved.text)
    }

    @Test
    fun `reset restores the built-in default`() = run {
        val manager = PromptManager()
        manager.save(AgentRole.CODER, "Custom")
        manager.reset(AgentRole.CODER)
        assertEquals(AgentPromptSource.DEFAULT, manager.resolve(AgentRole.CODER).source)
        assertFalse(manager.isCustom(AgentRole.CODER))
    }

    @Test
    fun `a disabled override falls back to the default`() = run {
        val manager = PromptManager()
        manager.save(AgentRole.CODER, "Custom", enabled = false)
        assertEquals(AgentPromptSource.DEFAULT, manager.resolve(AgentRole.CODER).source)
    }

    @Test
    fun `a blank override never disables the agent`() = run {
        val manager = PromptManager()
        manager.save(AgentRole.CODER, "   ")
        val resolved = manager.resolve(AgentRole.CODER)
        assertEquals(AgentPromptSource.DEFAULT, resolved.source)
        assertEquals(DefaultAgentPrompts.CODER, resolved.text)
    }

    @Test
    fun `persists across manager instances`() = run {
        val store = InMemoryAgentPromptStore()
        val first = PromptManager(DefaultAgentPromptRepository(store))
        first.save(AgentRole.EXPLORER, "Persisted explorer prompt")
        val second = PromptManager(DefaultAgentPromptRepository(store))
        assertEquals("Persisted explorer prompt", second.resolve(AgentRole.EXPLORER).text)
    }

    @Test
    fun `every role resolves to a non-blank prompt`() = run {
        val manager = PromptManager()
        AgentRole.entries.forEach { role ->
            assertTrue(manager.resolve(role).text.isNotBlank(), "role $role resolved blank")
        }
    }

    @Test
    fun `configs expose default and custom state for settings`() = run {
        val manager = PromptManager()
        manager.save(AgentRole.TESTER, "Custom tester")
        val configs = manager.configs().associateBy { it.role }
        assertTrue(configs.getValue(AgentRole.TESTER).isCustom)
        assertFalse(configs.getValue(AgentRole.MAIN).isCustom)
    }

    @Test
    fun `resolves template variables`() = run {
        val manager = PromptManager()
        manager.save(AgentRole.MAIN, "Workspace {{workspace}} file {{current_file}} in {{language}}")
        val resolved = manager.resolve(
            AgentRole.MAIN,
            PromptVariables.of(
                "workspace" to "w1",
                "current_file" to "A.kt",
                "language" to "Kotlin",
            ),
        )
        assertEquals("Workspace w1 file A.kt in Kotlin", resolved.text)
    }

    @Test
    fun `leaves unknown variables untouched and reports them`() {
        val resolution = PromptTemplate.resolve("Hello {{mystery}}", PromptVariables.of("workspace" to "w"))
        assertEquals("Hello {{mystery}}", resolution.text)
        assertEquals(setOf("mystery"), resolution.unknownVariables)
    }

    @Test
    fun `never substitutes secret-like variables`() {
        val resolution = PromptTemplate.resolve(
            "key={{api_key}} pass={{password}}",
            PromptVariables.of("api_key" to "sk-secret", "password" to "hunter2"),
        )
        assertFalse(resolution.text.contains("sk-secret"))
        assertFalse(resolution.text.contains("hunter2"))
        assertTrue(resolution.rejectedVariables.containsAll(setOf("api_key", "password")))
    }

    @Test
    fun `redacts a secret-looking value`() {
        val resolution = PromptTemplate.resolve(
            "v={{workspace}}",
            PromptVariables.of("workspace" to "API_KEY=abc123"),
        )
        assertFalse(resolution.text.contains("abc123"))
    }
}
