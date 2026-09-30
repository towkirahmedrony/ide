package com.agentx.app.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillMarkdownTest {

    private fun document(content: String) = SkillDocument("skills/sample/SKILL.md", content)

    @Test
    fun `parses a well formed skill`() {
        val parsed = SkillMarkdown.parse(
            document(
                """
                ---
                id: debugging
                name: Debugging
                description: Find the root cause first.
                version: 1.2
                roles: coder, debugger
                priority: high
                enabled: true
                ---

                # Instructions
                Read the error before changing code.
                """.trimIndent(),
            ),
            fallbackId = "folder-name",
        )

        val skill = assertIs<SkillParseResult.Valid>(parsed).skill
        assertEquals("debugging", skill.id)
        assertEquals("Debugging", skill.name)
        assertEquals("Find the root cause first.", skill.description)
        assertEquals("1.2", skill.version)
        assertEquals(setOf("CODER", "DEBUGGER"), skill.roles)
        assertEquals(SkillPriority.HIGH, skill.priority)
        assertTrue(skill.defaultEnabled)
        assertTrue(skill.instructions.contains("Read the error before changing code."))
        assertTrue(skill.valid)
    }

    @Test
    fun `derives the id from the folder when absent`() {
        val parsed = SkillMarkdown.parse(
            document(
                """
                ---
                name: My Skill
                ---
                Do the thing.
                """.trimIndent(),
            ),
            fallbackId = "my-folder",
        )
        assertEquals("my-folder", assertIs<SkillParseResult.Valid>(parsed).skill.id)
    }

    @Test
    fun `missing front matter is rejected`() {
        val parsed = SkillMarkdown.parse(document("# Just a heading\nNo front matter."))
        assertIs<SkillParseResult.Invalid>(parsed)
        assertTrue(parsed.problems.any { it.contains("front matter") })
    }

    @Test
    fun `unterminated front matter is rejected`() {
        val parsed = SkillMarkdown.parse(document("---\nname: Broken\nbody"))
        assertIs<SkillParseResult.Invalid>(parsed)
        assertTrue(parsed.problems.any { it.contains("not closed") })
    }

    @Test
    fun `missing name is rejected`() {
        val parsed = SkillMarkdown.parse(document("---\ndescription: no name\n---\nbody"))
        assertIs<SkillParseResult.Invalid>(parsed)
        assertTrue(parsed.problems.any { it.contains("name") })
    }

    @Test
    fun `missing body is rejected`() {
        val parsed = SkillMarkdown.parse(document("---\nname: No body\n---\n"))
        assertIs<SkillParseResult.Invalid>(parsed)
        assertTrue(parsed.problems.any { it.contains("instruction body") })
    }

    @Test
    fun `unknown agent role is rejected`() {
        val parsed = SkillMarkdown.parse(
            document("---\nname: Bad role\nroles: wizard\n---\nbody"),
        )
        assertIs<SkillParseResult.Invalid>(parsed)
        assertTrue(parsed.problems.any { it.contains("unknown agent role") })
    }

    @Test
    fun `path traversal in the id is rejected`() {
        val parsed = SkillMarkdown.parse(
            document("---\nid: ../escape\nname: Evil\n---\nbody"),
        )
        assertIs<SkillParseResult.Invalid>(parsed)
    }

    @Test
    fun `unsafe ids are not accepted by the safety check`() {
        assertFalse(SkillMarkdown.isSafeId("../escape"))
        assertFalse(SkillMarkdown.isSafeId("Bad Id"))
        assertTrue(SkillMarkdown.isSafeId("debugging"))
        assertTrue(SkillMarkdown.isSafeId("my.skill-2"))
    }

    @Test
    fun `encode round-trips through parse`() {
        val skill = SkillDefinition(
            id = "round-trip",
            name = "Round Trip",
            description = "One line description.",
            instructions = "Do the thing.",
            version = "2.0",
            source = SkillSource.IMPORTED,
            roles = setOf("CODER"),
            priority = SkillPriority.HIGH,
            defaultEnabled = true,
        )
        val parsed = SkillMarkdown.parse(
            SkillDocument("skills/round-trip/SKILL.md", SkillMarkdown.encode(skill)),
            fallbackId = "round-trip",
        )
        val decoded = assertIs<SkillParseResult.Valid>(parsed).skill
        assertEquals(skill.id, decoded.id)
        assertEquals(skill.name, decoded.name)
        assertEquals(skill.description, decoded.description)
        assertEquals(skill.roles, decoded.roles)
        assertEquals(skill.priority, decoded.priority)
        assertTrue(decoded.defaultEnabled)
        assertEquals(skill.instructions, decoded.instructions)
    }
}
