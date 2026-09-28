package com.agentx.app.context

import com.agentx.app.model.ModelRole
import com.agentx.app.model.ModelToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationContextTest {

    private val engine = DefaultContextEngine()

    @Test
    fun `conversation keeps the system prompt the task and the newest turns`() {
        val budget = ContextBudget(maxConversationMessages = 4)
        val context = engine.newRunContext("session", budget)
        context.start("system prompt", "the task")
        repeat(10) { index -> context.addAssistant("assistant $index") }

        val messages = context.messages()

        assertEquals(4, messages.size)
        assertEquals(ModelRole.SYSTEM, messages.first().role)
        assertEquals("system prompt", messages.first().content)
        assertEquals(ModelRole.USER, messages[1].role)
        assertEquals("the task", messages[1].content)
        assertEquals("assistant 9", messages.last().content)
        assertTrue(context.truncatedCount > 0)
    }

    @Test
    fun `conversation respects its character budget`() {
        val budget = ContextBudget(maxConversationMessages = 100, maxConversationChars = 60)
        val context = engine.newRunContext("session", budget)
        context.start("sys", "task")
        repeat(5) { index -> context.addAssistant("assistant message number $index") }

        val messages = context.messages()

        assertTrue(messages.sumOf { it.content.length } <= 60 + "assistant message number 4".length)
        assertEquals("sys", messages.first().content)
        assertEquals("task", messages[1].content)
        assertTrue(context.truncatedCount > 0)
    }

    @Test
    fun `a tool call and its result are dropped together`() {
        val budget = ContextBudget(maxConversationMessages = 5, maxConversationChars = 10_000)
        val context = engine.newRunContext("session", budget)
        context.start("sys", "task")
        context.addAssistant("", listOf(ModelToolCall(id = "c1", name = "read_file")))
        context.addToolResult(callId = "c1", toolName = "read_file", content = "file body")
        context.addAssistant("plain one")
        context.addAssistant("plain two")

        val messages = context.messages()

        assertTrue(messages.none { it.role == ModelRole.TOOL }, "an orphan tool result must never survive")
        assertEquals(4, messages.size)
        assertEquals(1, context.truncatedCount)
    }

    @Test
    fun `every surviving tool result keeps its assistant call`() {
        val budget = ContextBudget(maxConversationMessages = 4, maxConversationChars = 10_000)
        val context = engine.newRunContext("session", budget)
        context.start("sys", "task")
        repeat(3) { index ->
            context.addAssistant("", listOf(ModelToolCall(id = "c$index", name = "read_file")))
            context.addToolResult(callId = "c$index", toolName = "read_file", content = "body $index")
        }

        val messages = context.messages()

        messages.forEachIndexed { index, message ->
            if (message.role != ModelRole.TOOL) return@forEachIndexed
            val paired = messages.take(index).any { earlier ->
                earlier.role == ModelRole.ASSISTANT && earlier.toolCalls.any { it.id == message.toolCallId }
            }
            assertTrue(paired, "tool result ${message.toolCallId} lost its assistant call")
        }
    }

    @Test
    fun `tool results are truncated and recorded as context items`() {
        val budget = ContextBudget(maxToolResultChars = 100)
        val context = engine.newRunContext("session", budget)
        context.start("sys", "task")
        val content = "x".repeat(5_000)

        context.addToolResult(callId = "c1", toolName = "read_file", content = content, path = "src/main.kt")

        val toolMessage = context.messages().last()
        assertEquals(ModelRole.TOOL, toolMessage.role)
        assertEquals("c1", toolMessage.toolCallId)
        assertTrue(toolMessage.content.length < content.length)
        assertTrue(toolMessage.content.contains("truncated"))
        assertEquals(1, context.truncatedCount)

        val item = context.items().single()
        assertEquals(ContextSource.TOOL_RESULT, item.source)
        assertEquals("src/main.kt", item.path)
        assertEquals("read_file", item.metadata.toolId)
        assertEquals(ToolContextStatus.SUCCESS, item.metadata.toolStatus)
        assertEquals(ContextReason.TOOL_RESULT, item.metadata.selectedBecause)
        assertTrue(item.truncated)
        assertEquals(content.length, item.originalChars)
    }

    @Test
    fun `a tool result is recorded once per call`() {
        val context = engine.newRunContext("session", ContextBudget.DEFAULT)
        context.start("sys", "task")

        context.addToolResult(callId = "c1", toolName = "read_file", content = "first")
        context.addToolResult(callId = "c1", toolName = "read_file", content = "second")

        assertEquals(1, context.messages().count { it.role == ModelRole.TOOL })
        assertEquals(1, context.items().size)
        assertEquals("first", context.messages().last().content)
    }

    @Test
    fun `a failed tool result is recorded as a failure with higher relevance`() {
        val context = engine.newRunContext("session", ContextBudget.DEFAULT)
        context.start("sys", "task")

        context.addToolResult(
            callId = "c1",
            toolName = "run_command",
            content = "boom",
            status = ToolContextStatus.FAILURE,
        )

        val item = context.items().single()
        assertEquals(ToolContextStatus.FAILURE, item.metadata.toolStatus)
        assertTrue(item.relevance > ContextRelevance.TOOL_RESULT_SUCCESS)
        assertEquals("boom", context.messages().last().content)
    }

    @Test
    fun `restoring a conversation replaces the seed`() {
        val context = engine.newRunContext("session", ContextBudget.DEFAULT)
        context.start("sys", "task")
        context.addAssistant("first attempt")

        val saved = context.messages()
        val resumed = engine.newRunContext("other-session", ContextBudget.DEFAULT)
        resumed.restore(saved)

        assertEquals(saved, resumed.messages())
    }
}
