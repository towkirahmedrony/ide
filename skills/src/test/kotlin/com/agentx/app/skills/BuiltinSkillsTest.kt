package com.agentx.app.skills

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contracts for the shipped skill catalog.
 *
 * These guard the invariants a built-in has to hold: stable and unique ids, a valid
 * body, activation that changes nothing until the user opts in, and an anti-slop rule
 * set that stays a framework-agnostic filter instead of drifting into an aesthetic
 * blacklist or a pile of web-only rules.
 */
class BuiltinSkillsTest {

    private val antiSlop = BuiltinSkills.ANTI_SLOP_DESIGN

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    @Test
    fun `built-in ids are stable and unique`() {
        val ids = BuiltinSkills.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate built-in skill id among $ids")
        assertEquals("anti-slop-design", antiSlop.id)
        assertTrue("anti-slop-design" in ids)
    }

    @Test
    fun `every built-in is a valid, shipped definition`() {
        BuiltinSkills.all().forEach { skill ->
            assertTrue(skill.valid, "${skill.id} is invalid: ${skill.problems}")
            assertTrue(skill.name.isNotBlank(), "${skill.id} has no name")
            assertTrue(skill.description.isNotBlank(), "${skill.id} has no description")
            assertTrue(skill.instructions.isNotBlank(), "${skill.id} has no instructions")
            assertEquals(SkillSource.BUILTIN, skill.source, "${skill.id} is not shipped as a built-in")
        }
    }

    @Test
    fun `built-ins stay disabled by default so shipping one changes no behaviour`() {
        BuiltinSkills.all().forEach { skill ->
            assertFalse(skill.defaultEnabled, "${skill.id} would switch itself on")
        }
    }

    @Test
    fun `the anti-slop skill targets the roles that produce or judge UI`() {
        assertEquals(
            setOf("MAIN", "PLANNER", "CODER", "FAST_CODER", "REVIEWER"),
            antiSlop.roles,
        )
        assertEquals(SkillPriority.HIGH, antiSlop.priority)
    }

    @Test
    fun `the anti-slop rules carry stable AS- ids, each declared once, in order`() {
        val ids = RULE_ID.findAll(antiSlop.instructions).map { it.value }.toList()
        assertTrue(ids.isNotEmpty(), "the anti-slop skill declares no rule ids")
        assertEquals(ids.size, ids.toSet().size, "duplicate rule id among $ids")
        assertEquals(ids.sorted(), ids, "rule ids should read in ascending order")
        assertEquals(EXPECTED_RULES, ids.toSet())
    }

    @Test
    fun `every anti-slop concern this phase requires is covered`() {
        val body = antiSlop.instructions.lowercase()
        val concerns = mapOf(
            "purpose test" to "what does this serve",
            "hierarchy" to "primary action",
            "honesty" to "never invent",
            "placeholders" to "placeholders",
            "dead controls" to "fake buttons",
            "states" to "loading",
            "genericness" to "swapping the product name",
            "consistency" to "reuse existing components",
            "accessibility" to "contrast",
            "platform" to "target platform",
            "guardrail" to "cluster",
        )
        concerns.forEach { (concern, marker) ->
            assertTrue(marker in body, "the anti-slop skill does not cover $concern")
        }
    }

    @Test
    fun `the anti-slop skill bans no technique and imports no web-only rule`() {
        val body = antiSlop.instructions
        WEB_ONLY_MARKERS.forEach { marker ->
            assertFalse(marker in body, "web-only text in a framework-agnostic skill: '$marker'")
        }
        BAN_PHRASINGS.forEach { phrasing ->
            assertFalse(phrasing in body, "ban-style wording in a filter that must not ban techniques: '$phrasing'")
        }
        // The guardrail that keeps the skill a filter: clustered evidence, not a blacklist.
        assertTrue("Never reject" in body)
    }

    @Test
    fun `an enabled anti-slop skill resolves for its roles and for no others`() = run {
        val manager = DefaultSkillManager(store = InMemorySkillStore())
        manager.refresh()

        assertFalse(manager.isEnabled(antiSlop.id), "anti-slop must not be on until the user opts in")
        manager.setEnabled(antiSlop.id, true)

        TARGET_ROLES.forEach { role ->
            assertTrue(
                manager.resolveForAgent(role).any { it.id == antiSlop.id },
                "anti-slop did not resolve for $role",
            )
        }
        NON_TARGET_ROLES.forEach { role ->
            assertFalse(
                manager.resolveForAgent(role).any { it.id == antiSlop.id },
                "anti-slop leaked into $role",
            )
        }
    }

    @Test
    fun `the shipped catalog loads clean through a refresh`() = run {
        val manager = DefaultSkillManager(store = InMemorySkillStore())
        val refresh = manager.refresh()

        assertTrue(refresh.invalid.isEmpty(), "invalid built-ins: ${refresh.invalid.map { it.id to it.problems }}")
        assertEquals(
            BuiltinSkills.all().map { it.id }.sorted(),
            refresh.installed.map { it.id }.sorted(),
        )
    }

    private companion object {
        val RULE_ID = Regex("AS-\\d{3}")

        val EXPECTED_RULES = setOf(
            "AS-001", "AS-002", "AS-010", "AS-011", "AS-020", "AS-021",
            "AS-030", "AS-040", "AS-050", "AS-060", "AS-070",
        )

        val TARGET_ROLES = listOf("MAIN", "PLANNER", "CODER", "FAST_CODER", "REVIEWER")

        val NON_TARGET_ROLES = listOf(
            "EXPLORER", "RESEARCHER", "DEBUGGER", "TESTER", "SECURITY_REVIEWER", "DOCS", "COMMIT_PR",
        )

        /** Mechanics that only exist on one platform, so they must not appear as rules. */
        val WEB_ONLY_MARKERS = listOf(
            "100vh", "clamp(", ":focus-visible", "44px", "44 px", "Tailwind", "hamburger", "Lucide",
        )

        /** Wording that would turn the filter into a blacklist of techniques. */
        val BAN_PHRASINGS = listOf("never use", "do not use", "avoid using")
    }
}
