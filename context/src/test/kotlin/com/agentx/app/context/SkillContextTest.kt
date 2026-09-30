package com.agentx.app.context

import com.agentx.app.skills.DefaultSkillManager
import com.agentx.app.skills.InMemorySkillStore
import com.agentx.app.skills.SkillDefinition
import com.agentx.app.skills.SkillPriority
import com.agentx.app.skills.SkillSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillContextTest {

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private fun skill(
        id: String,
        roles: Set<String> = setOf("CODER"),
        instructions: String = "follow the $id instructions",
        priority: Int = SkillPriority.NORMAL,
    ) = SkillDefinition(
        id = id,
        name = "Skill $id",
        description = "Description for $id",
        instructions = instructions,
        source = SkillSource.BUILTIN,
        roles = roles,
        priority = priority,
    )

    private suspend fun manager(vararg skills: SkillDefinition): DefaultSkillManager {
        val manager = DefaultSkillManager(builtins = skills.toList(), store = InMemorySkillStore())
        manager.refresh()
        return manager
    }

    @Test
    fun `renders an enabled skill assigned to the role`() = run {
        val manager = manager(skill("coding"))
        manager.setEnabled("coding", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("CODER", ContextBudget.DEFAULT)

        assertEquals(1, context.items.size)
        val item = context.items.single()
        assertEquals(ContextSource.SKILL, item.source)
        assertEquals(ContextReason.SKILL, item.metadata.selectedBecause)
        assertEquals("Skill coding", item.metadata.attribute("skillName"))
        assertEquals("CODER", item.metadata.attribute("targetAgent"))
        assertTrue(context.rendered.contains("Skill coding"))
        assertTrue(context.rendered.contains("follow the coding instructions"))
    }

    @Test
    fun `no context is produced when the skill is disabled`() = run {
        val manager = manager(skill("coding"))
        val provider = SkillContextProvider(manager, DefaultContextEngine())
        assertTrue(provider.resolve("CODER", ContextBudget.DEFAULT).isEmpty)
    }

    @Test
    fun `only skills assigned to the role are injected`() = run {
        val manager = manager(
            skill("coding", roles = setOf("CODER")),
            skill("review", roles = setOf("REVIEWER")),
        )
        manager.setEnabled("coding", true)
        manager.setEnabled("review", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        assertEquals(listOf("skill:coding"), provider.resolve("CODER", ContextBudget.DEFAULT).items.map { it.id })
        assertEquals(listOf("skill:review"), provider.resolve("REVIEWER", ContextBudget.DEFAULT).items.map { it.id })
    }

    @Test
    fun `skill context respects the item budget`() = run {
        val manager = manager(skill("a"), skill("b"), skill("c"))
        listOf("a", "b", "c").forEach { manager.setEnabled(it, true) }
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("CODER", ContextBudget(maxSkillItems = 1))

        assertEquals(1, context.items.size)
    }

    @Test
    fun `long skill instructions are truncated to the character budget`() = run {
        val manager = manager(skill("big", instructions = "x".repeat(10_000)))
        manager.setEnabled("big", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val item = provider.resolve("CODER", ContextBudget(maxSkillChars = 200)).items.single()

        assertTrue(item.truncated, "expected the skill body to be truncated")
        assertTrue(item.chars <= 400, "kept ${item.chars} characters")
    }
}
