package com.agentx.app.context

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextBudgetTest {

    private val engine = DefaultContextEngine()

    @Test
    fun `the default budget is valid and an invalid one is reported`() {
        assertEquals(emptyList(), ContextBudget.DEFAULT.validate())
        assertTrue(ContextBudget(maxTotalChars = 0).validate().isNotEmpty())
        assertTrue(ContextBudget(maxFileChars = 0).validate().isNotEmpty())
        assertTrue(ContextBudget(maxConversationMessages = -1).validate().isNotEmpty())
        assertTrue(ContextBudget(maxTotalTokens = 0).validate().isNotEmpty())
        assertTrue(ContextBudget(maxDesignChars = 0).validate().isNotEmpty())
    }

    @Test
    fun `the design direction has its own, smaller ceiling than a file`() {
        val budget = ContextBudget.DEFAULT

        assertEquals(budget.maxDesignChars, budget.itemCharLimit(ContextSource.DESIGN))
        // A statement of design intent is injected into the system instruction, so it
        // is bounded like the skill block rather than like a source file.
        assertTrue(budget.maxDesignChars < budget.maxFileChars)
        assertEquals(ContextPriority.NORMAL, ContextSource.DESIGN.defaultPriority)
        assertEquals(ContextRelevance.PROJECT_DESIGN, ContextRelevance.defaultFor(ContextSource.DESIGN))
    }

    @Test
    fun `a derived model budget keeps the design ceiling usable and valid`() {
        val plan = ModelContextBudget.forModel(
            windowTokens = ModelContextBudget.MIN_CONTEXT_WINDOW_TOKENS,
            base = ContextBudget.DEFAULT,
        )

        assertEquals(emptyList(), plan.budget.validate())
        assertTrue(plan.budget.maxDesignChars > 0)
        // Narrowing the window narrows the design ceiling too, so a small model
        // receives a shortened direction instead of none at all.
        assertTrue(plan.budget.maxDesignChars <= ContextBudget.DEFAULT.maxDesignChars)
    }

    @Test
    fun `a token limit tightens the character ceiling`() {
        val budget = ContextBudget(maxTotalChars = 48_000, maxTotalTokens = 1_000, charsPerToken = 4)
        assertEquals(4_000, budget.charLimit)
        assertEquals(4, budget.estimateTokens(16))
    }

    @Test
    fun `truncation leaves short content untouched`() {
        val result = ContextTruncator.truncate("hello", 10)
        assertEquals("hello", result.text)
        assertFalse(result.truncated)
        assertEquals(5, result.originalChars)
    }

    @Test
    fun `truncation shortens large content and says how much was dropped`() {
        val content = "a".repeat(500)
        val result = ContextTruncator.truncate(content, 100)

        assertTrue(result.truncated)
        assertEquals(500, result.originalChars)
        assertTrue(result.text.length < content.length)
        assertTrue(result.text.startsWith("a".repeat(100)))
        assertTrue(result.text.contains("truncated"))
    }

    @Test
    fun `chunking splits content in order`() {
        assertEquals(listOf("ab", "cd", "e"), ContextTruncator.chunk("abcde", 2))
        assertEquals(emptyList(), ContextTruncator.chunk("", 2))
        assertFailsWith<IllegalArgumentException> { ContextTruncator.chunk("abc", 0) }
    }

    @Test
    fun `enforcement caps the number of files`() {
        val items = (1..5).map { contextItem("file:f$it.kt", ContextSource.FILE, ContextPriority.HIGH, 100.0) }

        val selection = engine.enforceBudget(items, ContextBudget(maxFileCount = 2))

        assertEquals(listOf("file:f1.kt", "file:f2.kt"), selection.items.map { it.id })
        assertEquals(3, selection.excluded.count { it.reason == ContextExclusionReason.OVER_FILE_LIMIT })
    }

    @Test
    fun `enforcement caps the total number of items`() {
        val items = (1..5).map { contextItem("file:f$it.kt", ContextSource.FILE, ContextPriority.HIGH, 100.0) }

        val selection = engine.enforceBudget(items, ContextBudget(maxItems = 3, maxFileCount = 10))

        assertEquals(3, selection.items.size)
        assertEquals(2, selection.excluded.count { it.reason == ContextExclusionReason.OVER_ITEM_LIMIT })
    }

    @Test
    fun `enforcement stops at the character budget`() {
        val items = (1..5).map {
            contextItem("file:f$it.kt", ContextSource.FILE, ContextPriority.HIGH, 100.0, content = "x".repeat(100))
        }

        val selection = engine.enforceBudget(items, ContextBudget(maxTotalChars = 250))

        assertEquals(2, selection.items.size)
        assertEquals(200, selection.usedChars)
        assertTrue(selection.usedChars <= selection.limitChars)
        assertEquals(3, selection.excluded.count { it.reason == ContextExclusionReason.OVER_CHAR_BUDGET })
    }

    @Test
    fun `enforcement keeps the current request even when it alone is large`() {
        val request = contextItem(
            "user:request",
            ContextSource.USER_MESSAGE,
            ContextPriority.CRITICAL,
            ContextRelevance.CURRENT_REQUEST,
            content = "q".repeat(400),
        )
        val file = contextItem("file:a.kt", ContextSource.FILE, ContextPriority.HIGH, 100.0, content = "y".repeat(100))

        val selection = engine.enforceBudget(listOf(request, file), ContextBudget(maxTotalChars = 100))

        assertEquals(listOf("user:request"), selection.items.map { it.id })
        assertTrue(selection.excluded.any { it.reason == ContextExclusionReason.OVER_CHAR_BUDGET })
    }

    @Test
    fun `enforcement truncates an oversized file item`() {
        val item = contextItem("file:big.kt", ContextSource.FILE, ContextPriority.HIGH, 100.0, content = "y".repeat(5_000))

        val selection = engine.enforceBudget(listOf(item), ContextBudget(maxFileChars = 100))

        val kept = selection.items.single()
        assertTrue(kept.truncated)
        assertEquals(5_000, kept.originalChars)
        assertTrue(kept.chars < 5_000)
        assertEquals("item budget", selection.truncated.single().reason)
    }

    @Test
    fun `tool results are bounded by the tool-result budget`() = runTest {
        val engine = testEngine(budget = ContextBudget(maxToolResultChars = 50))

        val result = engine.buildContext(
            ContextRequest(
                task = "go",
                toolResults = listOf(
                    ToolContextResult(
                        toolId = "search_files",
                        status = ToolContextStatus.SUCCESS,
                        content = "z".repeat(2_000),
                        path = "src",
                        callId = "c1",
                    ),
                ),
            ),
        )

        val item = result.items.single { it.source == ContextSource.TOOL_RESULT }
        assertEquals("tool:c1", item.id)
        assertTrue(item.truncated)
        assertEquals(2_000, item.originalChars)
        assertTrue(item.chars < 2_000)
        assertEquals("search_files", item.metadata.toolId)
        assertTrue(result.truncated.any { it.id == "tool:c1" })
    }

    @Test
    fun `only the configured number of tool results survives`() = runTest {
        val engine = testEngine(budget = ContextBudget(maxToolResultCount = 1))
        val results = (1..3).map { index ->
            ToolContextResult(
                toolId = "read_file",
                status = ToolContextStatus.SUCCESS,
                content = "content $index",
                callId = "c$index",
            )
        }

        val result = engine.buildContext(ContextRequest(task = "go", toolResults = results))

        assertEquals(1, result.itemsOf(ContextSource.TOOL_RESULT).size)
        assertEquals(2, result.excluded.count { it.reason == ContextExclusionReason.OVER_TOOL_RESULT_LIMIT })
    }
}
