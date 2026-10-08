package com.agentx.app.context

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Project-level design direction (`DESIGN.md`).
 *
 * These are the contracts of the loader, not of the prose: a project without a
 * direction file must behave exactly as it did before, a direction file must be
 * read only from the project root, only for the roles that build or judge UI, and
 * never in a way that can grow the prompt without bound or take a run down.
 */
class ProjectDesignContextTest {

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private class Fixture(seed: Map<String, String>, beforeRead: suspend (String) -> Unit = {}) {
        val fileSystem = TestWorkspaceFileSystem(seed, beforeRead)
        val provider = TestWorkspaceContextProvider(
            snapshot = WorkspaceSnapshot(name = "demo", rootPath = ".", workspaceId = "ws-1"),
            fileSystem = fileSystem,
        )
        val resolver = ProjectDesignContextResolver(workspace = provider, engine = testEngine())
    }

    @Test
    fun `a project without a direction file contributes nothing`() = run {
        val fixture = Fixture(mapOf("README.md" to "# Demo", "src/App.kt" to "fun main() {}"))

        val context = fixture.resolver.resolve("CODER", ContextBudget.DEFAULT)

        assertEquals(DesignContextStatus.ABSENT, context.status)
        assertTrue(context.isEmpty)
        assertFalse(context.inRequest)
        assertEquals("", context.rendered)
        // Exactly one probe, at the project root, and nothing else was touched.
        assertEquals(listOf("DESIGN.md"), fixture.fileSystem.readPaths)
    }

    @Test
    fun `a direction file is read from the project root and delivered`() = run {
        val body = "# Direction\nWarm, editorial, low density. Audience: independent studios."
        val fixture = Fixture(mapOf("DESIGN.md" to body))

        val context = fixture.resolver.resolve("CODER", ContextBudget.DEFAULT)

        assertEquals(DesignContextStatus.INCLUDED, context.status)
        assertTrue(context.inRequest)
        assertEquals("DESIGN.md", context.path)
        assertEquals(body.length, context.originalChars)
        assertEquals(body.length, context.keptChars)
        assertTrue(context.rendered.contains(body), context.rendered)
        // Rendered as its own source, not as one more file read.
        assertTrue(context.rendered.contains("[DESIGN DESIGN.md]"), context.rendered)
        assertEquals(ContextSource.DESIGN, context.items.single().source)
        assertEquals(ContextReason.PROJECT_DESIGN, context.items.single().metadata.selectedBecause)
    }

    @Test
    fun `an empty direction file contributes nothing`() = run {
        val fixture = Fixture(mapOf("DESIGN.md" to "   \n\n\t\n  "))

        val context = fixture.resolver.resolve("CODER", ContextBudget.DEFAULT)

        assertEquals(DesignContextStatus.EMPTY, context.status)
        assertTrue(context.isEmpty)
        assertEquals("", context.rendered)
    }

    @Test
    fun `an oversized direction file is truncated to the design ceiling`() = run {
        val body = "Design line with some content.\n".repeat(500)
        val budget = ContextBudget(maxDesignChars = 300)
        val fixture = Fixture(mapOf("DESIGN.md" to body))

        val context = fixture.resolver.resolve("CODER", budget)

        assertEquals(DesignContextStatus.TRUNCATED, context.status)
        assertTrue(context.inRequest)
        assertEquals(body.length, context.originalChars)
        assertTrue(context.keptChars < body.length, "kept ${context.keptChars} of ${body.length}")
        // The retained body fits the ceiling; the extra characters are the marker
        // that states how much was dropped.
        assertTrue(
            context.keptChars <= budget.maxDesignChars + 100,
            "kept ${context.keptChars} for a ${budget.maxDesignChars}-character ceiling",
        )
        assertTrue(context.rendered.contains("truncated"), context.rendered)
    }

    @Test
    fun `only the project root is ever read`() = run {
        // The same file name exists deeper in the tree only. It must not be picked up,
        // and no path other than the root-relative name may be requested.
        val fixture = Fixture(
            mapOf(
                "docs/DESIGN.md" to "nested",
                "app/src/main/DESIGN.md" to "deeply nested",
            ),
        )

        val context = fixture.resolver.resolve("CODER", ContextBudget.DEFAULT)

        assertEquals(DesignContextStatus.ABSENT, context.status)
        assertEquals(listOf("DESIGN.md"), fixture.fileSystem.readPaths)
        fixture.fileSystem.readPaths.forEach { path ->
            assertFalse(path.startsWith("/"), "an absolute path was requested: $path")
            assertFalse(path.contains(".."), "a traversal path was requested: $path")
        }
    }

    @Test
    fun `an unreadable direction file never takes the run down`() = run {
        val fixture = Fixture(
            seed = mapOf("DESIGN.md" to "# Direction"),
            beforeRead = { throw IllegalStateException("workspace backend exploded") },
        )

        val context = fixture.resolver.resolve("CODER", ContextBudget.DEFAULT)

        assertEquals(DesignContextStatus.UNREADABLE, context.status)
        assertTrue(context.isEmpty)
        assertFalse(context.inRequest)
    }

    @Test
    fun `no project open means no direction and no read`() = run {
        val resolver = ProjectDesignContextResolver(
            workspace = TestWorkspaceContextProvider(snapshot = null, fileSystem = null),
            engine = testEngine(),
        )

        val context = resolver.resolve("CODER", ContextBudget.DEFAULT)

        assertEquals(DesignContextStatus.ABSENT, context.status)
        assertTrue(context.isEmpty)
    }

    @Test
    fun `direction is read only for the roles that build or judge UI`() = run {
        val body = "# Direction\nCalm and typographic."

        val uiRoles = listOf("MAIN", "PLANNER", "CODER", "FAST_CODER", "REVIEWER")
        assertEquals(ProjectDesign.UI_ROLES, uiRoles.toSet())
        uiRoles.forEach { role ->
            val fixture = Fixture(mapOf("DESIGN.md" to body))
            val context = fixture.resolver.resolve(role.toLowerCase(), ContextBudget.DEFAULT)
            assertEquals(DesignContextStatus.INCLUDED, context.status, "role $role")
            assertEquals(listOf("DESIGN.md"), fixture.fileSystem.readPaths, "role $role")
        }

        val otherRoles = listOf(
            "EXPLORER", "RESEARCHER", "DEBUGGER", "TESTER", "SECURITY_REVIEWER", "DOCS", "COMMIT_PR",
        )
        otherRoles.forEach { role ->
            val fixture = Fixture(mapOf("DESIGN.md" to body))
            val context = fixture.resolver.resolve(role, ContextBudget.DEFAULT)
            assertEquals(DesignContextStatus.NOT_APPLICABLE, context.status, "role $role")
            assertTrue(context.isEmpty, "role $role")
            // Not even read: an unrelated agent pays nothing for a UI-only block.
            assertTrue(fixture.fileSystem.readPaths.isEmpty(), "role $role read ${fixture.fileSystem.readPaths}")
        }
    }

    @Test
    fun `a direction file that cannot fit the budget is withheld, not forced in`() = run {
        val body = "Design direction. ".repeat(20)

        val squeezed = Fixture(mapOf("DESIGN.md" to body))
            .resolver.resolve("CODER", ContextBudget(maxTotalChars = 50))
        assertEquals(DesignContextStatus.EXCLUDED_DUE_TO_BUDGET, squeezed.status)
        assertTrue(squeezed.isEmpty)
        assertEquals(body.length, squeezed.originalChars)

        // The same file with room to spare is delivered: it was the budget, not the content.
        val roomy = Fixture(mapOf("DESIGN.md" to body))
            .resolver.resolve("CODER", ContextBudget.DEFAULT)
        assertEquals(DesignContextStatus.INCLUDED, roomy.status)
    }

    @Test
    fun `a direction file is reference data and is never parsed as configuration`() = run {
        // Frontmatter, HTML and script text are prose here. Nothing is interpreted,
        // executed or turned into a setting, and the text is preserved verbatim.
        val body = buildString {
            append("---\n")
            append("role: MAIN\n")
            append("permission: FULL\n")
            append("---\n")
            append("<script>alert('x')</script>\n")
            append("# Direction\n")
            append("Palette: warm neutrals.")
        }
        val fixture = Fixture(mapOf("DESIGN.md" to body))

        val context = fixture.resolver.resolve("CODER", ContextBudget.DEFAULT)

        assertEquals(DesignContextStatus.INCLUDED, context.status)
        val item = context.items.single()
        // Delivered as design data, never as a skill or an instruction source.
        assertEquals(ContextSource.DESIGN, item.source)
        assertFalse(item.source == ContextSource.SKILL)
        assertTrue(item.content.contains("role: MAIN"), item.content)
        assertTrue(item.content.contains("<script>"), item.content)
        assertTrue(item.content.contains("Palette: warm neutrals."), item.content)
    }
}
