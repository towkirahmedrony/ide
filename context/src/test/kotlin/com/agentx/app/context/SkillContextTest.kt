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

    // --- Main Agent delivery -----------------------------------------------

    @Test
    fun `a skill assigned to MAIN reaches the main agent context`() = run {
        val manager = manager(skill("main-rules", roles = setOf("MAIN")))
        manager.setEnabled("main-rules", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget.DEFAULT)

        assertEquals(listOf("skill:main-rules"), context.items.map { it.id })
        assertTrue(context.rendered.contains("follow the main-rules instructions"))
        assertEquals("MAIN", context.items.single().metadata.attribute("targetAgent"))
        assertEquals(
            listOf(SkillContextStatus.INCLUDED),
            context.entries.map { it.status },
        )
    }

    @Test
    fun `an unassigned enabled skill is reported, not silently dropped`() = run {
        val manager = manager(skill("other-role", roles = setOf("REVIEWER")))
        manager.setEnabled("other-role", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget.DEFAULT)

        assertTrue(context.isEmpty)
        assertEquals(SkillContextStatus.NOT_ASSIGNED, context.entries.single().status)
        assertTrue(context.entries.single().reason.contains("REVIEWER"))
        assertEquals(1, context.count(SkillContextStatus.NOT_ASSIGNED))
    }

    @Test
    fun `a disabled skill assigned to MAIN is reported as disabled`() = run {
        val manager = manager(skill("main-rules", roles = setOf("MAIN")))
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget.DEFAULT)

        assertTrue(context.isEmpty)
        assertEquals(SkillContextStatus.DISABLED, context.entries.single().status)
        assertEquals("disabled in Settings", context.entries.single().reason)
    }

    @Test
    fun `a global skill reaches MAIN`() = run {
        val manager = manager(skill("everywhere", roles = emptySet()))
        manager.setEnabled("everywhere", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        assertEquals(listOf("skill:everywhere"), provider.resolve("MAIN", ContextBudget.DEFAULT).items.map { it.id })
    }

    // --- Overflow is observable --------------------------------------------

    @Test
    fun `a skill over the count limit is excluded with an explicit reason`() = run {
        val manager = manager(
            skill("a", roles = setOf("MAIN")),
            skill("b", roles = setOf("MAIN")),
            skill("c", roles = setOf("MAIN")),
        )
        listOf("a", "b", "c").forEach { manager.setEnabled(it, true) }
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget(maxSkillItems = 1))

        assertEquals(1, context.items.size)
        assertEquals(2, context.count(SkillContextStatus.EXCLUDED_DUE_TO_LIMIT))
        assertTrue(context.withheld.all { it.reason.contains("skill limit reached") })
    }

    @Test
    fun `a skill over the block ceiling is excluded due to budget`() = run {
        val manager = manager(
            skill("long", roles = setOf("MAIN"), instructions = "line of instructions\n".repeat(200)),
        )
        manager.setEnabled("long", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget(maxSkillChars = 20_000, maxSkillTotalChars = 200))

        assertTrue(context.isEmpty, "the block ceiling is the only limit that can hold the skill back")
        assertEquals(SkillContextStatus.EXCLUDED_DUE_TO_BUDGET, context.entries.single().status)
        assertEquals(0, context.entries.single().keptChars)
    }

    @Test
    fun `the skill block never exceeds the block ceiling`() = run {
        val manager = manager(
            *Array(8) { index -> skill("skill-$index", roles = setOf("MAIN"), instructions = "y".repeat(3_000)) },
        )
        (0..7).forEach { manager.setEnabled("skill-$it", true) }
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget(maxSkillTotalChars = 5_000))

        assertTrue(context.usedChars <= 5_000, "skill block used ${context.usedChars} characters")
        assertEquals(8, context.entries.size, "every installed skill must be accounted for")
        assertTrue(context.entries.all { it.reason.isNotBlank() }, "every outcome needs a reason")
        assertEquals(8, context.count(SkillContextStatus.INCLUDED) + context.count(SkillContextStatus.TRUNCATED) +
            context.count(SkillContextStatus.EXCLUDED_DUE_TO_LIMIT) + context.count(SkillContextStatus.EXCLUDED_DUE_TO_BUDGET))
    }

    @Test
    fun `an invalid skill is reported as invalid`() = run {
        val broken = skill("broken", roles = setOf("MAIN")).copy(problems = listOf("front matter is missing a 'name'"))
        val manager = manager(broken)
        manager.setEnabled("broken", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget.DEFAULT)

        assertTrue(context.isEmpty)
        assertEquals(SkillContextStatus.INVALID, context.entries.single().status)
    }

    @Test
    fun `exclusion and truncation are deterministic`() = run {
        val manager = manager(
            skill("a", roles = setOf("MAIN"), instructions = "a-line\n".repeat(400)),
            skill("b", roles = setOf("MAIN"), instructions = "b-line\n".repeat(400)),
        )
        listOf("a", "b").forEach { manager.setEnabled(it, true) }
        val provider = SkillContextProvider(manager, DefaultContextEngine())
        val budget = ContextBudget(maxSkillChars = 300, maxSkillTotalChars = 400)

        val first = provider.resolve("MAIN", budget)
        val second = provider.resolve("MAIN", budget)

        assertEquals(first.items.map { it.id }, second.items.map { it.id })
        assertEquals(first.rendered, second.rendered)
        assertEquals(first.entries, second.entries)
        assertTrue(first.rendered.contains("skill instructions truncated"))
        // Whole lines only: every kept instruction line is complete, never a fragment.
        val keptLines = first.rendered.lines()
        assertTrue(keptLines.any { it == "a-line" })
        assertTrue(keptLines.none { it.isNotBlank() && it != "a-line" && it.endsWith("a-lin") })
    }

    @Test
    fun `diagnostics never contain instruction text`() = run {
        val manager = manager(skill("secretive", roles = setOf("MAIN"), instructions = "do the secret thing"))
        manager.setEnabled("secretive", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val fields = provider.resolve("MAIN", ContextBudget.DEFAULT).diagnosticFields()

        assertTrue(fields.values.none { it.toString().contains("do the secret thing") })
        assertEquals(1, fields["skillsIncluded"])
    }
    // --- Per-message selection --------------------------------------------

    @Test
    fun `no selection keeps the role's own skills`() = run {
        val manager = manager(skill("a", roles = setOf("MAIN")), skill("b", roles = setOf("MAIN")))
        listOf("a", "b").forEach { manager.setEnabled(it, true) }
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val implicit = provider.resolve("MAIN", ContextBudget.DEFAULT)
        val explicitNull = provider.resolve("MAIN", ContextBudget.DEFAULT, null)

        assertEquals(listOf("skill:a", "skill:b"), implicit.items.map { it.id })
        assertEquals(implicit.items, explicitNull.items)
        assertEquals(implicit.entries, explicitNull.entries)
    }

    @Test
    fun `selecting a subset injects only the selected skills`() = run {
        val manager = manager(
            skill("a", roles = setOf("MAIN")),
            skill("b", roles = setOf("MAIN")),
            skill("c", roles = setOf("MAIN")),
        )
        listOf("a", "b", "c").forEach { manager.setEnabled(it, true) }
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget.DEFAULT, setOf("a", "c"))

        assertEquals(listOf("skill:a", "skill:c"), context.items.map { it.id })
        assertEquals(1, context.count(SkillContextStatus.NOT_SELECTED))
        val withheld = context.entries.single { it.id == "b" }
        assertEquals(SkillContextStatus.NOT_SELECTED, withheld.status)
        assertEquals("not selected for this message", withheld.reason)
    }

    @Test
    fun `multiple selected skills are all injected`() = run {
        val manager = manager(
            skill("a", roles = setOf("MAIN")),
            skill("b", roles = setOf("MAIN")),
        )
        listOf("a", "b").forEach { manager.setEnabled(it, true) }
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget.DEFAULT, setOf("a", "b"))

        assertEquals(2, context.items.size)
        assertEquals(listOf(SkillContextStatus.INCLUDED, SkillContextStatus.INCLUDED), context.entries.map { it.status })
        assertEquals("selected for this message", context.entries.first().reason)
    }

    @Test
    fun `a disabled skill is rejected even when selected`() = run {
        val manager = manager(skill("a", roles = setOf("MAIN")))
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget.DEFAULT, setOf("a"))

        assertTrue(context.isEmpty)
        assertEquals(SkillContextStatus.DISABLED, context.entries.single().status)
    }

    @Test
    fun `an invalid skill is rejected even when selected`() = run {
        val broken = skill("broken", roles = setOf("MAIN")).copy(problems = listOf("front matter is missing a 'name'"))
        val manager = manager(broken)
        manager.setEnabled("broken", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget.DEFAULT, setOf("broken"))

        assertTrue(context.isEmpty)
        assertEquals(SkillContextStatus.INVALID, context.entries.single().status)
    }

    @Test
    fun `a skill the role is not assigned cannot be added by selecting it`() = run {
        val manager = manager(
            skill("mine", roles = setOf("MAIN")),
            skill("theirs", roles = setOf("REVIEWER")),
        )
        manager.setEnabled("mine", true)
        manager.setEnabled("theirs", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget.DEFAULT, setOf("mine", "theirs"))

        assertEquals(listOf("skill:mine"), context.items.map { it.id })
        assertEquals(SkillContextStatus.NOT_ASSIGNED, context.entries.single { it.id == "theirs" }.status)
    }

    @Test
    fun `an unknown id selects nothing`() = run {
        val manager = manager(skill("a", roles = setOf("MAIN")))
        manager.setEnabled("a", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget.DEFAULT, setOf("does-not-exist"))

        assertTrue(context.isEmpty)
        assertEquals(SkillContextStatus.NOT_SELECTED, context.entries.single().status)
    }

    @Test
    fun `selection keeps the budget and the accounting intact`() = run {
        val manager = manager(
            skill("a", roles = setOf("MAIN")),
            skill("b", roles = setOf("MAIN")),
            skill("c", roles = setOf("MAIN")),
        )
        listOf("a", "b", "c").forEach { manager.setEnabled(it, true) }
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget(maxSkillItems = 1), setOf("a", "b"))

        assertEquals(1, context.items.size, "the count limit still applies to a selection")
        assertEquals(3, context.entries.size, "every installed skill is still accounted for")
        assertEquals(1, context.count(SkillContextStatus.EXCLUDED_DUE_TO_LIMIT))
        assertEquals(1, context.count(SkillContextStatus.NOT_SELECTED))
        assertEquals(1, context.diagnosticFields()["skillsNotSelected"])
        assertTrue(context.entries.all { it.reason.isNotBlank() })
    }
}

