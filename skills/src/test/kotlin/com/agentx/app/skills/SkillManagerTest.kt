package com.agentx.app.skills

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillManagerTest {

    private val builtin = SkillDefinition(
        id = "builtin",
        name = "Builtin",
        description = "Shipped skill",
        instructions = "Follow the built-in rules.",
        source = SkillSource.BUILTIN,
        roles = setOf("CODER"),
    )

    private val globalBuiltin = SkillDefinition(
        id = "global",
        name = "Global",
        description = "Applies to every agent",
        instructions = "Global rules.",
        source = SkillSource.BUILTIN,
    )

    private fun markdown(id: String, roles: String = "TESTER"): SkillDocument = SkillDocument(
        path = "skills/$id/SKILL.md",
        content = """
            ---
            id: $id
            name: ${id.replaceFirstChar { it.uppercase() }}
            description: A workspace skill.
            roles: $roles
            ---
            Workspace instructions for $id.
        """.trimIndent(),
    )

    private fun newManager(
        store: SkillStore = InMemorySkillStore(),
        documents: List<SkillDocument> = emptyList(),
    ): DefaultSkillManager = DefaultSkillManager(
        builtins = listOf(builtin, globalBuiltin),
        sources = listOf(MarkdownSkillSource(SkillDocumentSource { documents })),
        store = store,
    )

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    @Test
    fun `discovers built-ins and workspace skills`() = run {
        val manager = newManager(documents = listOf(markdown("workspace-skill")))
        val refresh = manager.refresh()
        assertEquals(
            listOf("builtin", "global", "workspace-skill").sorted(),
            refresh.installed.map { it.id }.sorted(),
        )
        assertNotNull(manager.find("workspace-skill"))
    }

    @Test
    fun `skills are disabled until explicitly enabled`() = run {
        val manager = newManager()
        manager.refresh()
        assertFalse(manager.isEnabled("builtin"))
        assertTrue(manager.enabled().isEmpty())
    }

    @Test
    fun `enabled skills resolve for their assigned role only`() = run {
        val manager = newManager()
        manager.refresh()
        manager.setEnabled("builtin", true)
        assertEquals(listOf("builtin"), manager.resolveForAgent("CODER").map { it.id })
        assertTrue(manager.resolveForAgent("EXPLORER").none { it.id == "builtin" })
    }

    @Test
    fun `a global skill resolves for every agent`() = run {
        val manager = newManager()
        manager.refresh()
        manager.setEnabled("global", true)
        assertTrue(manager.resolveForAgent("CODER").any { it.id == "global" })
        assertTrue(manager.resolveForAgent("REVIEWER").any { it.id == "global" })
    }

    @Test
    fun `assignments can be changed from settings`() = run {
        val manager = newManager()
        manager.refresh()
        manager.setEnabled("builtin", true)
        manager.setRoles("builtin", setOf("TESTER"))
        assertTrue(manager.resolveForAgent("CODER").none { it.id == "builtin" })
        assertTrue(manager.resolveForAgent("TESTER").any { it.id == "builtin" })
    }

    @Test
    fun `import accepts a valid skill and rejects duplicates`() = run {
        val manager = newManager()
        manager.refresh()
        val raw = markdown("imported-one").content
        val imported = assertIs<SkillImportResult.Imported>(manager.import(raw))
        assertEquals(SkillSource.IMPORTED, manager.find(imported.skill.id)?.source)

        val duplicate = assertIs<SkillImportResult.Rejected>(manager.import(raw))
        assertTrue(duplicate.reasons.any { it.contains("already installed") })
    }

    @Test
    fun `import rejects malformed skills`() = run {
        val manager = newManager()
        manager.refresh()
        val rejected = assertIs<SkillImportResult.Rejected>(manager.import("# no front matter"))
        assertTrue(rejected.reasons.isNotEmpty())
    }

    @Test
    fun `import rejects an id that collides with a built-in`() = run {
        val manager = newManager()
        manager.refresh()
        val rejected = assertIs<SkillImportResult.Rejected>(
            manager.import(markdown("builtin").content),
        )
        assertTrue(rejected.reasons.any { it.contains("already installed") })
    }

    @Test
    fun `only imported skills can be removed`() = run {
        val manager = newManager()
        manager.refresh()
        val imported = assertIs<SkillImportResult.Imported>(manager.import(markdown("removable").content))
        assertTrue(manager.remove(imported.skill.id))
        assertNull(manager.find(imported.skill.id))
        assertFalse(manager.remove("builtin"))
    }

    @Test
    fun `state persists across manager instances`() = run {
        val store = InMemorySkillStore()
        val first = newManager(store = store)
        first.refresh()
        first.setEnabled("builtin", true)
        first.setRoles("builtin", setOf("REVIEWER"))
        assertIs<SkillImportResult.Imported>(first.import(markdown("persisted").content))

        val second = newManager(store = store)
        val refreshed = second.refresh()
        assertTrue(refreshed.installed.any { it.id == "persisted" })
        assertTrue(second.isEnabled("builtin"))
        assertTrue(second.resolveForAgent("REVIEWER").any { it.id == "builtin" })
        assertTrue(second.resolveForAgent("CODER").none { it.id == "builtin" })
    }

    @Test
    fun `reset state restores defaults`() = run {
        val store = InMemorySkillStore()
        val manager = newManager(store = store)
        manager.refresh()
        manager.setEnabled("builtin", true)
        manager.resetState()
        assertFalse(manager.isEnabled("builtin"))
    }

    @Test
    fun `clear imported removes only imported skills`() = run {
        val store = InMemorySkillStore()
        val manager = newManager(store = store)
        manager.refresh()
        assertIs<SkillImportResult.Imported>(manager.import(markdown("gone").content))
        manager.clearImported()
        assertNull(manager.find("gone"))
        assertNotNull(manager.find("builtin"))
    }

    @Test
    fun `malformed workspace documents are skipped, not fatal`() = run {
        val manager = newManager(
            documents = listOf(
                SkillDocument("skills/bad/SKILL.md", "not a skill at all"),
                markdown("good-one"),
            ),
        )
        val refresh = manager.refresh()
        assertEquals(listOf("builtin", "global", "good-one").sorted(), refresh.installed.map { it.id }.sorted())
    }
}
