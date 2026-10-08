package com.agentx.app.agent

import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.skills.BuiltinSkills
import com.agentx.app.skills.SkillDocument
import com.agentx.app.skills.SkillMarkdown
import com.agentx.app.skills.SkillParseResult
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Built-in skills carry their roles as plain strings and are not validated by the
 * workspace `SKILL.md` parser, whose accepted role list is deliberately narrower.
 *
 * That leaves this module, where both the catalog and [AgentRole] are visible, as the
 * only place a built-in's roles can be checked against the roles that actually exist.
 * It also records why the anti-slop skill needs no parser change: it targets PLANNER,
 * CODER and REVIEWER through the built-in path, which already accepts every real role.
 */
class BuiltinSkillRoleTest {

    private val knownRoles = AgentRole.entries.map { it.name }.toSet()

    @Test
    fun `every built-in skill role is a real agent role`() {
        BuiltinSkills.all().forEach { skill ->
            val unknown = skill.roles - knownRoles
            assertTrue(unknown.isEmpty(), "${skill.id} targets role(s) that do not exist: ${unknown.sorted()}")
        }
    }

    @Test
    fun `the anti-slop skill reaches PLANNER without widening the workspace parser`() {
        val antiSlop = BuiltinSkills.ANTI_SLOP_DESIGN

        assertTrue(antiSlop.roles.isNotEmpty(), "the anti-slop skill targets no role")
        assertTrue(
            antiSlop.roles.all { it in knownRoles },
            "the anti-slop skill names a role that does not exist: ${(antiSlop.roles - knownRoles).sorted()}",
        )
        assertTrue(
            antiSlop.roles.any { it !in SkillMarkdown.KNOWN_ROLES },
            "this contract exists because a built-in may target a role the workspace parser does not accept",
        )

        // The workspace parser is untouched by this phase: a hand-written SKILL.md
        // naming PLANNER is still rejected, so the catalog stays the only route to it.
        val parsed = SkillMarkdown.parse(
            SkillDocument(
                path = "skills/anti-slop/SKILL.md",
                content = """
                    ---
                    id: anti-slop-workspace
                    name: Anti-Slop Workspace
                    roles: planner
                    ---
                    Body.
                """.trimIndent(),
            ),
        )
        assertTrue(
            parsed is SkillParseResult.Invalid,
            "the workspace role list was widened; update this contract deliberately",
        )
    }
}
