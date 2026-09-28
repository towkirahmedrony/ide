package com.agentx.app.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextItemTest {

    @Test
    fun `context item creation follows its source`() {
        val file = ContextItem(id = "file:a.kt", source = ContextSource.FILE, content = "a")
        assertEquals(ContextSource.FILE.defaultPriority, file.priority)
        assertEquals(ContextRelevance.defaultFor(ContextSource.FILE), file.relevance)
        assertEquals(1, file.chars)
        assertEquals(1, file.originalChars)
        assertFalse(file.truncated)
        assertEquals(ContextMetadata(), file.metadata)

        val request = ContextItem(id = "user:request", source = ContextSource.USER_MESSAGE, content = "do it")
        assertEquals(ContextPriority.CRITICAL, request.priority)
        assertEquals(ContextRelevance.CURRENT_REQUEST, request.relevance)
    }

    @Test
    fun `context item without an id is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ContextItem(id = "   ", source = ContextSource.FILE, content = "x")
        }
    }

    @Test
    fun `metadata records why an item was selected`() {
        val metadata = ContextMetadata(
            reason = "Result of read_file",
            selectedBecause = ContextReason.TOOL_RESULT,
            toolId = "read_file",
            toolStatus = ToolContextStatus.SUCCESS,
            recencyRank = 0,
        ).withAttributes("call" to "c1")

        assertEquals(ContextReason.TOOL_RESULT, metadata.selectedBecause)
        assertEquals("read_file", metadata.toolId)
        assertEquals(ToolContextStatus.SUCCESS, metadata.toolStatus)
        assertEquals("c1", metadata.attribute("call"))
    }

    @Test
    fun `ranking follows the promised selection order`() {
        val items = listOf(
            contextItem("conversation:1", ContextSource.CONVERSATION, ContextPriority.LOW, ContextRelevance.CONVERSATION),
            contextItem("workspace:info", ContextSource.WORKSPACE_INFO, ContextPriority.LOW, ContextRelevance.WORKSPACE_INFO),
            contextItem("file:found.kt", ContextSource.FILE, ContextPriority.NORMAL, ContextRelevance.SEARCH_RESULT),
            contextItem("tool:c1", ContextSource.TOOL_RESULT, ContextPriority.NORMAL, ContextRelevance.TOOL_RESULT_SUCCESS),
            contextItem("file:recent.kt", ContextSource.FILE, ContextPriority.HIGH, ContextRelevance.recentFile(0)),
            contextItem("file:mentioned.kt", ContextSource.FILE, ContextPriority.HIGH, ContextRelevance.MENTIONED_FILE),
            contextItem("user:request", ContextSource.USER_MESSAGE, ContextPriority.CRITICAL, ContextRelevance.CURRENT_REQUEST),
        )

        assertEquals(
            listOf(
                "user:request",
                "file:mentioned.kt",
                "file:recent.kt",
                "tool:c1",
                "file:found.kt",
                "workspace:info",
                "conversation:1",
            ),
            ContextRanker.rank(items).map { it.id },
        )
    }

    @Test
    fun `ranking is stable for items of equal rank`() {
        val first = contextItem("file:a.kt", ContextSource.FILE, ContextPriority.HIGH, 10.0)
        val second = contextItem("file:b.kt", ContextSource.FILE, ContextPriority.HIGH, 10.0)
        val third = contextItem("file:c.kt", ContextSource.FILE, ContextPriority.HIGH, 10.0)

        assertEquals(
            listOf("file:a.kt", "file:b.kt", "file:c.kt"),
            ContextRanker.rank(listOf(first, second, third)).map { it.id },
        )
        assertEquals(
            listOf("file:a.kt", "file:b.kt", "file:c.kt"),
            ContextRanker.rank(listOf(first, second, third)).map { it.id },
        )
    }

    @Test
    fun `an explicitly mentioned file outranks a recently used one`() {
        val mentioned = contextItem(
            "file:mentioned.kt",
            ContextSource.FILE,
            ContextPriority.HIGH,
            ContextRelevance.MENTIONED_FILE,
            path = "mentioned.kt",
        )
        val recent = contextItem(
            "file:recent.kt",
            ContextSource.FILE,
            ContextPriority.HIGH,
            ContextRelevance.recentFile(0),
            path = "recent.kt",
        )

        assertEquals(listOf(mentioned.id, recent.id), ContextRanker.rank(listOf(recent, mentioned)).map { it.id })
    }

    @Test
    fun `recent file relevance decreases with age`() {
        assertTrue(ContextRelevance.recentFile(0) > ContextRelevance.recentFile(1))
        assertTrue(ContextRelevance.recentFile(1) > ContextRelevance.recentFile(5))
        assertTrue(ContextRelevance.recentFile(50) >= ContextRelevance.RECENT_FILE_MIN)
    }

    @Test
    fun `a tool result outranks a search result but not a file the user named`() {
        val tool = contextItem("tool:c1", ContextSource.TOOL_RESULT, ContextPriority.NORMAL, ContextRelevance.TOOL_RESULT_SUCCESS)
        val search = contextItem("file:found.kt", ContextSource.FILE, ContextPriority.NORMAL, ContextRelevance.SEARCH_RESULT)
        val mentioned = contextItem(
            "file:mentioned.kt",
            ContextSource.FILE,
            ContextPriority.HIGH,
            ContextRelevance.MENTIONED_FILE,
        )

        val order = ContextRanker.rank(listOf(search, tool, mentioned)).map { it.id }
        assertEquals(listOf("file:mentioned.kt", "tool:c1", "file:found.kt"), order)
    }
}
