package com.agentx.app.context

import com.agentx.app.skills.DefaultSkillManager
import com.agentx.app.skills.InMemorySkillStore
import com.agentx.app.skills.SkillSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Workspace `skills/<id>/SKILL.md` discovery: it must read through the Context
 * Engine's workspace port, reuse the existing validation, and stay harmless when
 * there is no workspace or the document is unusable.
 */
class WorkspaceSkillSourceTest {

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private fun skillFile(
        id: String,
        body: String = "Workspace instructions for $id.",
        idField: String? = id,
        roles: String = "MAIN",
    ): String = buildString {
        append("---\n")
        idField?.let { append("id: ").append(it).append('\n') }
        append("name: ").append(id.replaceFirstChar { it.uppercase() }).append('\n')
        append("description: A workspace skill.\n")
        append("roles: ").append(roles).append('\n')
        append("---\n")
        append(body)
    }

    private fun workspace(vararg files: Pair<String, String>): WorkspaceContextProvider =
        TestWorkspaceContextProvider(
            fileSystem = TestWorkspaceFileSystem(files.toMap()),
        )

    private suspend fun manager(workspace: WorkspaceContextProvider): DefaultSkillManager {
        val manager = DefaultSkillManager(
            builtins = emptyList(),
            sources = listOf(WorkspaceSkillSource.discovery(workspace)),
            store = InMemorySkillStore(),
        )
        manager.refresh()
        return manager
    }

    @Test
    fun `a valid workspace SKILL_md is discovered`() = run {
        val manager = manager(workspace("skills/workspace-rules/SKILL.md" to skillFile("workspace-rules")))

        val discovered = manager.installed().single()
        assertEquals("workspace-rules", discovered.id)
        assertEquals(SkillSource.WORKSPACE, discovered.source)
        assertTrue(discovered.valid, discovered.problems.toString())
    }

    @Test
    fun `the folder name is the id when the front matter omits it`() = run {
        val manager = manager(
            workspace("skills/from-folder/SKILL.md" to skillFile("from-folder", idField = null)),
        )

        assertEquals("from-folder", manager.installed().single().id)
    }

    @Test
    fun `discovered skills still respect enablement and role assignment`() = run {
        val manager = manager(workspace("skills/workspace-rules/SKILL.md" to skillFile("workspace-rules")))

        // Discovered is not the same as offered: it is off until the user enables it.
        assertTrue(manager.resolveForAgent("MAIN").isEmpty())

        manager.setEnabled("workspace-rules", true)
        assertEquals(listOf("workspace-rules"), manager.resolveForAgent("MAIN").map { it.id })

        // And an enabled workspace skill is not injected into a role it was not assigned.
        assertTrue(manager.resolveForAgent("REVIEWER").isEmpty())
    }

    @Test
    fun `an enabled workspace skill reaches the main agent context`() = run {
        val manager = manager(workspace("skills/workspace-rules/SKILL.md" to skillFile("workspace-rules")))
        manager.setEnabled("workspace-rules", true)
        val provider = SkillContextProvider(manager, DefaultContextEngine())

        val context = provider.resolve("MAIN", ContextBudget.DEFAULT)

        assertEquals(listOf("skill:workspace-rules"), context.items.map { it.id })
        assertTrue(context.rendered.contains("Workspace instructions for workspace-rules"))
        assertEquals(SkillContextStatus.INCLUDED, context.entries.single().status)
    }

    @Test
    fun `a malformed or missing document is skipped without failing`() = run {
        val manager = manager(
            workspace(
                "skills/broken/SKILL.md" to "not a skill at all",
                "skills/empty/SKILL.md" to "",
                "skills/no-file/notes.md" to "---\nname: Nope\n---\nbody",
                "skills/good/SKILL.md" to skillFile("good"),
            ),
        )

        assertEquals(listOf("good"), manager.installed().map { it.id })
    }

    @Test
    fun `discovery is deterministic`() = run {
        val manager = manager(
            workspace(
                "skills/zeta/SKILL.md" to skillFile("zeta"),
                "skills/alpha/SKILL.md" to skillFile("alpha"),
            ),
        )

        assertEquals(listOf("alpha", "zeta"), manager.installed().map { it.id })
        assertEquals(listOf("alpha", "zeta"), manager.installed().map { it.id })
    }

    @Test
    fun `no active workspace yields no workspace skills and no failure`() = run {
        val manager = manager(TestWorkspaceContextProvider(snapshot = null, fileSystem = null))

        assertTrue(manager.installed().isEmpty())
    }

    @Test
    fun `a workspace without a skill directory yields no workspace skills`() = run {
        val manager = manager(workspace("src/Main.kt" to "fun main() {}"))

        assertTrue(manager.installed().isEmpty())
    }

    @Test
    fun `the source never reads outside the skill directory`() = run {
        val fileSystem = TestWorkspaceFileSystem(
            mapOf(
                "skills/demo/SKILL.md" to skillFile("demo"),
                "secrets/private.md" to "---\nname: Private\n---\nleak",
            ),
        )

        val documents = WorkspaceSkillSource(TestWorkspaceContextProvider(fileSystem = fileSystem))
            .documents()

        assertEquals(listOf("skills/demo/SKILL.md"), documents.map { it.path })
    }
}
