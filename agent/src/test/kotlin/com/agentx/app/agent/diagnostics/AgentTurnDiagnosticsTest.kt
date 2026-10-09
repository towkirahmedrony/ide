package com.agentx.app.agent.diagnostics

import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentRunRequest
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.core.logging.ForgeLoggers
import com.agentx.app.core.logging.LogLevel
import com.agentx.app.core.logging.LogRecord
import com.agentx.app.core.logging.LogSink
import com.agentx.app.model.ModelConfig
import com.agentx.app.model.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Developer Log's agent diagnostics: one correlation id per turn, explicit
 * lifecycle states, real failure reasons, coalesced streaming, no cross-turn mixing
 * and no credentials.
 */
class AgentTurnDiagnosticsTest {

    private class RecordingSink : LogSink {
        private val items = mutableListOf<LogRecord>()
        val records: List<LogRecord> get() = synchronized(items) { items.toList() }
        override fun write(record: LogRecord) {
            synchronized(items) { items += record }
        }
    }

    private fun logger(sink: LogSink) = ForgeLoggers.create(LogLevel.DEBUG, sink)

    private fun config() = ModelConfig(
        providerId = "openai",
        baseUrl = "http://localhost:8080/v1",
        model = "gpt-test",
    )

    private fun request() = AgentRunRequest(
        prompt = "explain this file",
        workspaceId = "w1",
        selectedFile = "src/Main.kt",
    )

    private fun List<LogRecord>.ofEvent(event: String): List<LogRecord> =
        filter { it.fields["event"] == event }

    @Test
    fun `a successful turn records input request output and completion under one id`() {
        val sink = RecordingSink()
        val diagnostics = AgentTurnDiagnostics(
            logger = logger(sink),
            sessionId = "s1",
            correlationId = "corr-a",
            clock = { 0L },
        )

        diagnostics.started(request(), config())
        diagnostics.onEvent(
            AgentEvent.ModelSelected("s1", AgentRole.MAIN, "openai", "gpt-test", "openai", null, true, 0),
        )
        diagnostics.onEvent(AgentEvent.OutputDelta("s1", "hello", 0))
        diagnostics.onEvent(AgentEvent.OutputDelta("s1", " world", 0))
        diagnostics.onEvent(
            AgentEvent.ToolRequested("s1", "call-1", "read_file", AgentRole.MAIN, emptyMap(), 0),
        )
        diagnostics.onEvent(AgentEvent.ToolCallFinished("s1", "read_file", true, "read 12 lines", 0))
        diagnostics.finish(AgentResult(sessionId = "s1", status = AgentStatus.COMPLETED, summary = "done"))

        val records = sink.records
        assertTrue(records.isNotEmpty())
        assertTrue(records.all { it.fields["correlationId"] == "corr-a" }, "every record carries the turn id")
        assertTrue(records.all { it.fields["sessionId"] == "s1" })
        assertTrue(records.all { it.fields["component"] == "agent" }, "agent records are tagged for the Agent category")

        assertTrue(records.ofEvent("agent.turn.started").isNotEmpty(), "input/request logged")
        assertTrue(records.ofEvent("agent.model.selected").isNotEmpty(), "model/provider logged")
        assertTrue(records.ofEvent("agent.stream.started").isNotEmpty(), "streaming start logged")
        assertTrue(records.ofEvent("agent.tool.requested").isNotEmpty(), "tool selection logged")
        assertTrue(records.ofEvent("agent.tool.finished").isNotEmpty(), "tool result logged")
        assertTrue(records.ofEvent("agent.turn.completed").isNotEmpty(), "completion logged")
    }

    @Test
    fun `a provider failure records the real error and the correlation id`() {
        val sink = RecordingSink()
        val diagnostics = AgentTurnDiagnostics(logger(sink), sessionId = "s1", correlationId = "corr-b")

        diagnostics.started(request(), config())
        val cause = IllegalStateException("upstream 429: rate limit exceeded")
        diagnostics.finish(
            AgentResult(
                sessionId = "s1",
                status = AgentStatus.FAILED,
                summary = "request failed",
                errors = listOf(
                    AgentError(
                        code = AgentErrorCode.MODEL_FAILURE,
                        message = "provider rate limited the request",
                        role = AgentRole.MAIN,
                        cause = cause,
                    ),
                ),
            ),
        )

        val failed = sink.records.ofEvent("agent.turn.failed").single()
        assertEquals("ERROR", failed.level.name)
        assertEquals("corr-b", failed.fields["correlationId"])
        assertEquals("MODEL_FAILURE", failed.fields["errorCode"])
        assertTrue(failed.fields["errorMessage"].toString().contains("rate limited"))
        assertEquals("java.lang.IllegalStateException", failed.fields["errorClass"])
        assertTrue(failed.fields["stackTrace"].toString().contains("AgentTurnDiagnosticsTest"))
        assertEquals(1, sink.records.ofEvent("agent.turn.failed").size, "an outcome is logged once")
    }

    @Test
    fun `a tool execution failure records the tool name details and turn`() {
        val sink = RecordingSink()
        val diagnostics = AgentTurnDiagnostics(logger(sink), sessionId = "s1", correlationId = "corr-c")

        diagnostics.started(request(), config())
        diagnostics.onEvent(
            AgentEvent.ToolRequested("s1", "call-9", "run_command", AgentRole.MAIN, emptyMap(), 0),
        )
        diagnostics.onEvent(
            AgentEvent.ToolCallFinished("s1", "run_command", false, "exit code 1: permission denied", 0),
        )

        val finished = sink.records.ofEvent("agent.tool.finished").single()
        assertEquals("WARN", finished.level.name)
        assertEquals("run_command", finished.fields["toolName"])
        assertEquals(false, finished.fields["success"])
        assertTrue(finished.fields["summary"].toString().contains("permission denied"))
        assertEquals("corr-c", finished.fields["correlationId"])
        assertEquals("s1", finished.fields["sessionId"])
    }

    @Test
    fun `streaming produces lifecycle diagnostics without one entry per chunk`() {
        val sink = RecordingSink()
        val diagnostics = AgentTurnDiagnostics(
            logger = logger(sink),
            sessionId = "s1",
            correlationId = "corr-d",
            clock = { 0L },
        )

        diagnostics.started(request(), config())
        repeat(5_000) { diagnostics.onEvent(AgentEvent.OutputDelta("s1", "x", 0)) }
        diagnostics.finish(AgentResult(sessionId = "s1", status = AgentStatus.COMPLETED, summary = "done"))

        val stream = sink.records.filter {
            (it.fields["event"] as? String)?.startsWith("agent.stream") == true
        }
        assertTrue(stream.any { it.fields["event"] == "agent.stream.started" })
        assertTrue(stream.any { it.fields["event"] == "agent.stream.completed" })
        assertTrue(stream.size <= 6, "stream diagnostics stay bounded, got ${stream.size}")
        assertTrue(sink.records.size < 20, "5000 chunks must not become 5000 log entries")
    }

    @Test
    fun `concurrent turns do not mix their events`() {
        val sink = RecordingSink()
        val first = AgentTurnDiagnostics(logger(sink), sessionId = "sA", correlationId = "corr-a")
        val second = AgentTurnDiagnostics(logger(sink), sessionId = "sB", correlationId = "corr-b")

        first.started(request(), config())
        second.started(request(), config())
        first.onEvent(AgentEvent.OutputDelta("sA", "from a", 0))
        second.onEvent(AgentEvent.OutputDelta("sB", "from b", 0))
        first.finish(AgentResult(sessionId = "sA", status = AgentStatus.COMPLETED, summary = "a"))
        second.finish(AgentResult(sessionId = "sB", status = AgentStatus.COMPLETED, summary = "b"))

        val a = sink.records.filter { it.fields["correlationId"] == "corr-a" }
        val b = sink.records.filter { it.fields["correlationId"] == "corr-b" }
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
        assertTrue(a.all { it.fields["sessionId"] == "sA" }, "turn A only ever carries session A")
        assertTrue(b.all { it.fields["sessionId"] == "sB" }, "turn B only ever carries session B")
    }

    @Test
    fun `a turn rejected before the provider is still logged with the reason`() {
        val sink = RecordingSink()
        val diagnostics = AgentTurnDiagnostics(logger(sink), sessionId = "s1", correlationId = "corr-e")

        diagnostics.rejected("No model is online.", config())

        val failed = sink.records.ofEvent("agent.turn.failed").single()
        assertEquals("ERROR", failed.level.name)
        assertTrue(failed.fields["reason"].toString().contains("No model is online"))
        assertEquals("corr-e", failed.fields["correlationId"])
    }

    @Test
    fun `credentials are redacted from tool arguments`() {
        val sink = RecordingSink()
        val diagnostics = AgentTurnDiagnostics(logger(sink), sessionId = "s1", correlationId = "corr-f")

        diagnostics.started(request(), config())
        diagnostics.onEvent(
            AgentEvent.ToolRequested(
                sessionId = "s1",
                toolCallId = "call-1",
                toolName = "http_request",
                role = AgentRole.MAIN,
                arguments = mapOf(
                    "authorization" to JsonValue.Str("Bearer sk-supersecret"),
                    "body" to JsonValue.Str("password=hunter2"),
                ),
                timestampMillis = 0,
            ),
        )

        val arguments = sink.records.ofEvent("agent.tool.requested").single().fields["arguments"].toString()
        assertFalse(arguments.contains("sk-supersecret"), arguments)
        assertFalse(arguments.contains("hunter2"), arguments)
        assertTrue(arguments.contains("authorization=[REDACTED]"), arguments)
    }
}
