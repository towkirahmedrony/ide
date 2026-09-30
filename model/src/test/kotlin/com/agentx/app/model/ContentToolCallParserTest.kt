package com.agentx.app.model

import com.agentx.app.model.json.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentToolCallParserTest {

    @Test
    fun `parses a name and arguments object`() {
        val calls = ContentToolCallParser.parse(
            """{"name":"read_file","arguments":{"path":"package.json"}}""",
        )
        assertEquals(1, calls.size)
        assertEquals("read_file", calls.single().name)
        assertEquals("package.json", calls.single().arguments.stringOrNull("path"))
    }

    @Test
    fun `parses sibling argument keys without an arguments wrapper`() {
        val calls = ContentToolCallParser.parse("""{"name":"read_file","path":"Auth.kt"}""")
        assertEquals("read_file", calls.single().name)
        assertEquals("Auth.kt", calls.single().arguments.stringOrNull("path"))
    }

    @Test
    fun `parses a fenced json tool call`() {
        val calls = ContentToolCallParser.parse(
            """
            ```json
            {"name":"list_directory","arguments":{"path":"."}}
            ```
            """.trimIndent(),
        )
        assertEquals(listOf("list_directory"), calls.map { it.name })
    }

    @Test
    fun `parses an OpenAI style function wrapper in content`() {
        val calls = ContentToolCallParser.parse(
            """{"id":"c1","type":"function","function":{"name":"search_files","arguments":"{\"query\":\"main\"}"}}""",
        )
        assertEquals("search_files", calls.single().name)
        assertEquals("c1", calls.single().id)
        assertEquals("main", calls.single().arguments.stringOrNull("query"))
    }

    @Test
    fun `parses a tool_calls array embedded in content`() {
        val calls = ContentToolCallParser.parse(
            """{"tool_calls":[{"name":"read_file","arguments":{"path":"a.kt"}},{"name":"read_file","arguments":{"path":"b.kt"}}]}""",
        )
        assertEquals(listOf("a.kt", "b.kt"), calls.map { it.arguments.stringOrNull("path") })
    }

    @Test
    fun `ignores ordinary assistant prose`() {
        assertTrue(ContentToolCallParser.parse("The main config is in Cargo.toml.").isEmpty())
        assertTrue(ContentToolCallParser.parse("{\"ok\":true}").isEmpty())
        assertTrue(ContentToolCallParser.parse("{\"name\":\"demo\"}").isEmpty())
    }

    @Test
    fun `normalize promotes content JSON into toolCalls and clears it from content`() {
        val normalized = ContentToolCallParser.normalize(
            ModelResponse(
                model = "m",
                providerId = "p",
                content = """{"name":"read_file","arguments":{"path":"package.json"}}""",
            ),
        )
        assertEquals("read_file", normalized.toolCalls.single().name)
        assertTrue(normalized.content.isBlank())
        assertEquals(ModelFinishReason.TOOL_CALLS, normalized.finishReason)
    }

    @Test
    fun `normalize keeps structured toolCalls and does not duplicate content JSON`() {
        val call = ModelToolCall(id = "c1", name = "read_file", arguments = emptyMap())
        val normalized = ContentToolCallParser.normalize(
            ModelResponse(
                model = "m",
                providerId = "p",
                content = """{"name":"read_file","arguments":{"path":"x"}}""",
                toolCalls = listOf(call),
                finishReason = ModelFinishReason.TOOL_CALLS,
            ),
        )
        assertEquals(listOf("c1"), normalized.toolCalls.map { it.id })
    }
}
