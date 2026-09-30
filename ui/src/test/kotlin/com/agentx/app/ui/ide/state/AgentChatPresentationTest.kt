package com.agentx.app.ui.ide.state

import com.agentx.app.ui.ide.data.PersistedAgentMessage
import com.agentx.app.ui.ide.data.PersistedMessageKind
import com.agentx.app.ui.ide.model.ChatMessageKind
import com.agentx.app.ui.ide.model.InlineSpan
import com.agentx.app.ui.ide.model.MessageBlock
import com.agentx.app.ui.ide.model.MessageState
import com.agentx.app.ui.ide.model.ToolRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentChatPresentationTest {

    // ───────────────────────────── Markdown ─────────────────────────────

    @Test
    fun `headings bullets and numbered lists parse into distinct blocks`() {
        val blocks = AgentChatPresentation.parseBlocks(
            """
            # Title

            Some paragraph text.

            - first
            - second

            1. one
            2. two
            """.trimIndent(),
        )

        assertTrue(blocks[0] is MessageBlock.Heading)
        assertEquals(1, (blocks[0] as MessageBlock.Heading).level)
        assertTrue(blocks.any { it is MessageBlock.Paragraph })
        val bullets = blocks.filterIsInstance<MessageBlock.BulletList>().single()
        assertEquals(2, bullets.items.size)
        val numbered = blocks.filterIsInstance<MessageBlock.NumberedList>().single()
        assertEquals(2, numbered.items.size)
    }

    @Test
    fun `a fenced code block keeps its language and body verbatim`() {
        val text = "Fix:\n\n```kotlin\nclass Example {\n    val x = 1\n}\n```\n\nDone."
        val blocks = AgentChatPresentation.parseBlocks(text)

        val code = blocks.filterIsInstance<MessageBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("class Example {"))
        assertTrue(code.code.contains("val x = 1"))
        // The fence must not be rendered as part of the prose.
        assertFalse(code.code.contains("```"))
    }

    @Test
    fun `an unterminated fence while streaming still renders as code`() {
        val blocks = AgentChatPresentation.parseBlocks("```python\ndef run():\n    pass")
        val code = blocks.filterIsInstance<MessageBlock.Code>().single()
        assertEquals("python", code.language)
        assertTrue(code.code.contains("def run():"))
    }

    @Test
    fun `inline code bold italic and safe links are recognised`() {
        val spans = AgentChatPresentation.parseInline(
            "use `run()` and **bold** and *italic* and [docs](https://example.com)",
        )

        assertTrue(spans.any { it is InlineSpan.Code && it.text == "run()" })
        assertTrue(spans.any { it is InlineSpan.Bold && it.text == "bold" })
        assertTrue(spans.any { it is InlineSpan.Italic && it.text == "italic" })
        val link = spans.filterIsInstance<InlineSpan.Link>().single()
        assertEquals("https://example.com", link.url)
    }

    @Test
    fun `unsafe link schemes are not linkified`() {
        val spans = AgentChatPresentation.parseInline("[click](javascript:alert(1))")
        assertTrue(spans.none { it is InlineSpan.Link })
    }

    @Test
    fun `plain text flattens blocks for copy`() {
        val blocks = AgentChatPresentation.parseBlocks("# Title\n\n- one\n\n```\ncode\n```")
        val plain = AgentChatPresentation.plainText(blocks)
        assertTrue(plain.contains("Title"))
        assertTrue(plain.contains("one"))
        assertTrue(plain.contains("code"))
    }

    // ───────────────────────────── Highlighting ─────────────────────────────

    @Test
    fun `kotlin highlighting finds keywords strings comments and numbers`() {
        val tokens = AgentChatPresentation.highlightCode(
            "fun main() { val x = \"hi\" // note\n  42 }",
            "kotlin",
        )
        val kinds = tokens.associate { it.text to it.kind }
        assertEquals(CodeTokenKind.KEYWORD, kinds["fun"])
        assertEquals(CodeTokenKind.KEYWORD, kinds["val"])
        assertEquals(CodeTokenKind.STRING, kinds["\"hi\""])
        assertTrue(tokens.any { it.kind == CodeTokenKind.COMMENT && it.text.contains("note") })
        assertTrue(tokens.any { it.kind == CodeTokenKind.NUMBER && it.text == "42" })
    }

    @Test
    fun `hash comments are highlighted only for hash-comment languages`() {
        val python = AgentChatPresentation.highlightCode("# comment\nx = 1", "python")
        assertTrue(python.any { it.kind == CodeTokenKind.COMMENT && it.text.startsWith("#") })

        val kotlin = AgentChatPresentation.highlightCode("val x = 1", "kotlin")
        assertTrue(kotlin.none { it.kind == CodeTokenKind.COMMENT })
    }

    @Test
    fun `an unknown language still highlights without failing`() {
        val tokens = AgentChatPresentation.highlightCode("fun x", "brainfuck")
        assertTrue(tokens.isNotEmpty())
    }

    // ───────────────────────────── Tool mapping ─────────────────────────────

    @Test
    fun `tool names become upper snake case labels`() {
        assertEquals("SEARCH_FILES", AgentChatPresentation.toolDisplayName("search_files"))
        assertEquals("READ_FILE", AgentChatPresentation.toolDisplayName("readFile"))
        assertEquals("RUN_COMMAND", AgentChatPresentation.toolDisplayName("run-command"))
    }

    @Test
    fun `tool details redact secrets and drop empty previews`() {
        val redacted = AgentChatPresentation.sanitizeToolDetail("api_key=sk-live-123456 path=main.kt")
        assertEquals("api_key=[REDACTED] path=main.kt", redacted)
        assertEquals(null, AgentChatPresentation.sanitizeToolDetail("(no arguments)"))
        assertEquals(null, AgentChatPresentation.sanitizeToolDetail(""))
    }

    // ───────────────────────────── Formatting ─────────────────────────────

    @Test
    fun `durations are human readable`() {
        assertEquals("12.4s", AgentChatPresentation.formatDuration(12_400))
        assertEquals("0.8s", AgentChatPresentation.formatDuration(800))
        assertEquals("1m 03s", AgentChatPresentation.formatDuration(63_000))
        assertEquals("12s", AgentChatPresentation.formatElapsedSeconds(12_400))
    }

    @Test
    fun `relative times are compact`() {
        val now = 1_000_000_000L
        assertEquals("just now", AgentChatPresentation.relativeTime(now - 10_000, now))
        assertEquals("5m ago", AgentChatPresentation.relativeTime(now - 5 * 60_000, now))
        assertEquals("2h ago", AgentChatPresentation.relativeTime(now - 2 * 3_600_000, now))
        assertEquals("3d ago", AgentChatPresentation.relativeTime(now - 3 * 86_400_000, now))
    }

    // ───────────────────────────── Persistence mapping ─────────────────────────────

    @Test
    fun `persisted assistant markdown becomes blocks rather than raw text`() {
        val ui = AgentChatPresentation.persistedMessageToUi(
            PersistedAgentMessage(
                id = "m1",
                kind = PersistedMessageKind.ASSISTANT,
                text = "Here you go:\n\n```kt\nclass Example {}\n```",
                timestampMillis = 10L,
                modelId = "qwen2.5",
            ),
        )

        assertEquals(ChatMessageKind.ASSISTANT, ui.kind)
        assertEquals(MessageState.COMPLETE, ui.state)
        assertEquals("qwen2.5", ui.modelId)
        assertTrue(ui.blocks.any { it is MessageBlock.Code })
    }

    @Test
    fun `persisted tool entries become tool cards and group under the reply`() {
        val transcript = listOf(
            PersistedAgentMessage("u1", PersistedMessageKind.USER, "find auth"),
            PersistedAgentMessage(
                id = "t1",
                kind = PersistedMessageKind.TOOL,
                text = "search_files · query=auth",
                toolName = "search_files",
                toolArguments = "query=auth",
                toolResult = "2 matches in AuthRepository.kt",
                toolSuccess = true,
                timestampMillis = 20L,
            ),
            PersistedAgentMessage("a1", PersistedMessageKind.ASSISTANT, "Found it."),
        )

        val ui = AgentChatPresentation.persistedTranscriptToUi(transcript)
        val tool = ui.single { it.kind == ChatMessageKind.TOOL }.tool!!
        assertEquals("SEARCH_FILES", tool.displayName)
        assertEquals(ToolRunStatus.COMPLETED, tool.status)
        assertEquals("query=auth", tool.detail)
        assertEquals("2 matches in AuthRepository.kt", tool.summary)

        val assistant = ui.single { it.kind == ChatMessageKind.ASSISTANT }
        assertEquals(1, assistant.activities.size)
        assertEquals("\"auth\"", assistant.activities.first().label)
        assertEquals("SEARCH_FILES", assistant.activities.first().toolName?.uppercase())
    }

    @Test
    fun `persisted errors map to a readable error card with a code`() {
        val ui = AgentChatPresentation.persistedMessageToUi(
            PersistedAgentMessage(
                id = "e1",
                kind = PersistedMessageKind.ERROR,
                text = "Connection to model timed out.",
                errorCode = "TIMEOUT",
            ),
        )
        assertEquals(ChatMessageKind.ERROR, ui.kind)
        assertEquals("Request failed", ui.error?.title)
        assertEquals("TIMEOUT", ui.error?.code)
        assertTrue(ui.error?.retryable == true)
    }

    @Test
    fun `a persisted session never mixes sessions because ids are preserved`() {
        val a = AgentChatPresentation.persistedTranscriptToUi(
            listOf(PersistedAgentMessage("a1", PersistedMessageKind.USER, "session a")),
        )
        val b = AgentChatPresentation.persistedTranscriptToUi(
            listOf(PersistedAgentMessage("b1", PersistedMessageKind.USER, "session b")),
        )
        assertEquals("a1", a.single().id)
        assertEquals("b1", b.single().id)
        assertFalse(a.single().rawText == b.single().rawText)
    }
}
