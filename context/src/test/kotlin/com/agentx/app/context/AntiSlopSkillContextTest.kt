package com.agentx.app.context

import com.agentx.app.skills.BuiltinSkills
import com.agentx.app.skills.DefaultSkillManager
import com.agentx.app.skills.InMemorySkillStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The anti-slop skill is injected on every UI turn, so it has to fit the per-skill
 * character ceiling the Context Engine enforces. A skill that is silently shortened
 * loses whichever rules fall past the cut, which would make the filter unreliable
 * exactly where it is needed.
 *
 * This is also the cheapest end-to-end check that a built-in survives the real path:
 * SkillManager -> SkillContextProvider -> budget -> rendered block.
 */
class AntiSlopSkillContextTest {

    private val antiSlopId = BuiltinSkills.ANTI_SLOP_DESIGN.id

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private suspend fun managerWithAntiSlopEnabled(): DefaultSkillManager {
        val manager = DefaultSkillManager(store = InMemorySkillStore())
        manager.refresh()
        manager.setEnabled(antiSlopId, true)
        return manager
    }

    /** Mirrors [SkillContextProvider]'s own rendering of a skill, name and description included. */
    private fun documentedBody(): String {
        val skill = BuiltinSkills.ANTI_SLOP_DESIGN
        return skill.name + "\n" + skill.description + "\n\n" + skill.instructions.trim()
    }

    @Test
    fun `the anti-slop skill stays inside the per-skill context ceiling`() {
        val ceiling = ContextBudget.DEFAULT.maxSkillChars
        val documented = documentedBody()
        assertTrue(
            documented.length <= ceiling,
            "the anti-slop skill is ${documented.length} characters, over the $ceiling-character ceiling",
        )
    }

    @Test
    fun `the anti-slop skill reaches the model whole, never truncated`() = run {
        val provider = SkillContextProvider(managerWithAntiSlopEnabled(), DefaultContextEngine())

        val context = provider.resolve("CODER", ContextBudget.DEFAULT)

        val entry = context.entries.single { it.id == antiSlopId }
        assertEquals(SkillContextStatus.INCLUDED, entry.status, entry.reason)
        assertEquals(0, context.count(SkillContextStatus.TRUNCATED))
        assertTrue(context.delivered.any { it.id == antiSlopId })
        // The last rule must survive: the guardrail is what stops the filter becoming a blacklist.
        assertTrue(context.rendered.contains("AS-070"), "the guardrail rule was dropped from the rendered block")
    }

    @Test
    fun `a role the skill does not target receives no anti-slop context`() = run {
        val provider = SkillContextProvider(managerWithAntiSlopEnabled(), DefaultContextEngine())

        val context = provider.resolve("EXPLORER", ContextBudget.DEFAULT)

        assertTrue(context.isEmpty, "anti-slop reached a role it is not assigned to")
        assertEquals(SkillContextStatus.NOT_ASSIGNED, context.entries.single { it.id == antiSlopId }.status)
    }
}
