package com.agentx.app.agent.conversation

import com.agentx.app.agent.domain.AgentError
import com.agentx.app.agent.domain.AgentErrorCode
import com.agentx.app.agent.domain.AgentPlan
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.AgentStep
import com.agentx.app.agent.domain.ToolActionRecord
import com.agentx.app.agent.orchestrator.InMemoryAgentSessionStore
import com.agentx.app.context.ContextBudget
import com.agentx.app.model.ModelRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Session-scoped conversation history: the guarantees the Agent depends on to
 * understand "it" and "that" across turns, without leaking between sessions.
 */
class ConversationHistoryTest {

    private fun history(store: ConversationStore = InMemoryConversationStore()): ConversationHistory {
        var tick = 1_000L
        var counter = 0
        return ConversationHistory(
            conversations = store,
            sessions = InMemoryAgentSessionStore(),
            clock = { tick += 10; tick },
            ids = { "gen-${++counter}" },
        )
    }

    private fun ConversationHistory.assistant(sessionId: String, id: String, text: String) {
        append(
            sessionId,
            ConversationMessage(
                id = id,
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = MessageContent(text = text),
            ),
        )
    }

    private fun completed(summary: String, vararg files: String): AgentResult = AgentResult(
        sessionId = "sess-a",
        status = AgentStatus.COMPLETED,
        summary = summary,
        filesInspected = files.toList(),
    )

    // --- sessions ----------------------------------------------------------

    @Test
    fun `a new session starts with an empty conversation`() {
        val history = history()

        val created = history.createSession(workspaceId = "ws", sessionId = "sess-a")

        assertEquals("sess-a", created.id)
        assertTrue(created.messages.isEmpty())
        assertEquals(SessionTitle.DEFAULT, created.session.title)
        assertEquals(AgentStatus.IDLE, created.session.status)
        assertEquals(emptyList(), history.modelMessages("sess-a"))
    }

    @Test
    fun `ensureSession reuses the existing session and records the model`() {
        val history = history()
        history.ensureSession(sessionId = "sess-a", workspaceId = "ws")

        val reopened = history.ensureSession(
            sessionId = "sess-a",
            workspaceId = "ws",
            modelProviderId = "openai-compatible",
            modelId = "Qwen/Qwen2.5-Coder-14B-Instruct",
        )

        assertEquals("sess-a", reopened.id)
        assertEquals("openai-compatible", reopened.session.modelProviderId)
        assertEquals("Qwen/Qwen2.5-Coder-14B-Instruct", reopened.session.modelId)
        assertEquals(1, history.conversations("ws").size)
    }

    @Test
    fun `only root sessions are listed`() {
        val history = history()
        history.createSession(workspaceId = "ws", sessionId = "sess-a")
        history.createSession(
            workspaceId = "ws",
            role = AgentRole.EXPLORER,
            parentSessionId = "sess-a",
            sessionId = "sess-child",
        )

        assertEquals(listOf("sess-a"), history.conversations("ws").map { it.id })
    }

    @Test
    fun `sessions in another workspace are not listed`() {
        val history = history()
        history.createSession(workspaceId = "ws-a", sessionId = "sess-a")
        history.createSession(workspaceId = "ws-b", sessionId = "sess-b")

        assertEquals(listOf("sess-a"), history.conversations("ws-a").map { it.id })
        assertEquals(2, history.conversations().size)
    }

    @Test
    fun `a session is only reachable through its owning workspace`() {
        val history = history()
        history.createSession(workspaceId = "ws-a", sessionId = "sess-a")
        history.createSession(workspaceId = "ws-b", sessionId = "sess-b")

        assertNotNull(history.owned("ws-a", "sess-a"))
        // A neighbour project cannot reach another project's session, exactly as if
        // it did not exist.
        assertNull(history.owned("ws-a", "sess-b"))
        assertNull(history.owned("ws-b", "sess-a"))
        assertNull(history.owned("ws-c", "sess-a"))
    }

    @Test
    fun `a legacy session with no owner is preserved but invisible to every project`() {
        val history = history()
        // Sessions written before chat was project-scoped carry no workspace id and
        // are never arbitrarily attributed to a project.
        history.createSession(workspaceId = null, sessionId = "legacy-a")
        history.recordUser("legacy-a", "old global chat")

        // Not reachable through any project...
        assertNull(history.owned("ws-a", "legacy-a"))
        assertEquals(emptyList(), history.conversations("ws-a").map { it.id })

        // ...and not deleted: it survives as an orphan, readable only by its own id.
        assertNotNull(history.open("legacy-a"))
        assertEquals("old global chat", history.open("legacy-a")!!.messages.single().content.text)
    }

    // --- ordering and persistence -----------------------------------------

    @Test
    fun `messages keep their order and reload with the session`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")

        history.recordUser("sess-a", "Find the authentication code.")
        history.assistant("sess-a", "m-2", "It is in AuthRepository.kt.")
        history.recordUser("sess-a", "How does the refresh token work?")

        val reloaded = history.open("sess-a")

        assertNotNull(reloaded)
        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            reloaded.messages.map { it.role },
        )
        assertEquals(
            listOf("Find the authentication code.", "It is in AuthRepository.kt.", "How does the refresh token work?"),
            reloaded.messages.map { it.content.text },
        )
        assertEquals("sess-a", reloaded.messages.first().sessionId)
    }

    /**
     * The headline requirement: the second user message must be resolvable from
     * the history the model is handed, without the user repeating themselves.
     */
    @Test
    fun `the next request can resolve what the previous turn was about`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")

        history.recordUser("sess-a", "Find the authentication code.")
        history.assistant("sess-a", "m-2", "I found it in AuthRepository.kt.")
        history.recordTool(
            sessionId = "sess-a",
            toolName = "ReadFile",
            arguments = """{"path":"AuthRepository.kt"}""",
            result = "class AuthRepository { fun refreshToken() }",
            success = true,
            toolCallId = "call-1",
        )
        history.recordUser("sess-a", "Explain the refresh token logic.")

        val request = history.modelMessages("sess-a")

        assertTrue(
            request.any { it.role == ModelRole.USER && it.content.contains("Find the authentication code") },
            "the earlier user turn must be replayed",
        )
        assertTrue(
            request.any { it.content.contains("AuthRepository.kt") },
            "the earlier tool result must be replayed",
        )
        assertEquals("Explain the refresh token logic.", request.last().content)
        assertEquals(ModelRole.USER, request.last().role)
    }

    @Test
    fun `two sessions never share messages or context`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")
        history.createSession(workspaceId = null, sessionId = "sess-b")

        history.recordUser("sess-a", "The terminal keeps crashing on startup.")
        history.recordUser("sess-b", "Redesign the settings screen.")

        val sessionA = history.modelMessages("sess-a")
        val sessionB = history.modelMessages("sess-b")

        assertEquals(listOf("The terminal keeps crashing on startup."), sessionA.map { it.content })
        assertEquals(listOf("Redesign the settings screen."), sessionB.map { it.content })
        assertFalse(sessionA.any { it.content.contains("settings screen") })
        assertFalse(sessionB.any { it.content.contains("terminal keeps crashing") })
    }

    @Test
    fun `a conversation written to a store is restored by a fresh history`() {
        val store = InMemoryConversationStore()
        val first = history(store)
        first.createSession(workspaceId = "ws", sessionId = "sess-a")
        first.recordUser("sess-a", "Let us investigate why the terminal crashes.")
        first.append(
            "sess-a",
            ConversationMessage(
                id = "m-2",
                sessionId = "sess-a",
                role = MessageRole.ASSISTANT,
                content = MessageContent(text = "Starting with the pty session."),
                metadata = MessageMetadata(
                    status = MessageStatus.COMPLETED,
                    timestampMillis = 42L,
                    modelProviderId = "openai-compatible",
                    modelId = "qwen",
                ),
            ),
        )

        // A brand new history over the same store models an app restart.
        val restarted = history(store)
        val restored = restarted.open("sess-a")

        assertNotNull(restored)
        assertEquals(2, restored.messages.size)
        assertEquals("Starting with the pty session.", restored.messages[1].content.text)
        assertEquals("qwen", restored.messages[1].metadata.modelId)
        assertEquals("ws", restored.workspaceId)
    }

    // --- tool, sub-agent and error history --------------------------------

    @Test
    fun `tool history keeps structured fields`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")

        val tool = history.recordTool(
            sessionId = "sess-a",
            toolName = "SearchFiles",
            arguments = """{"query":"login"}""",
            result = "3 matches",
            success = true,
            toolCallId = "call-9",
        )

        assertEquals(MessageRole.TOOL, tool.role)
        assertEquals("SearchFiles", tool.content.toolName)
        assertEquals("""{"query":"login"}""", tool.content.toolArguments)
        assertEquals("3 matches", tool.content.toolResult)
        assertEquals("call-9", tool.content.toolCallId)
        assertEquals(true, tool.metadata.toolSuccess)

        val asModel = history.modelMessages("sess-a").single()
        assertEquals(ModelRole.TOOL, asModel.role)
        assertEquals("call-9", asModel.toolCallId)
        assertEquals("SearchFiles", asModel.name)
    }

    @Test
    fun `a failed tool call is preserved as a failure`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")

        history.recordTool(
            sessionId = "sess-a",
            toolName = "ReadFile",
            arguments = """{"path":"content://saf/1"}""",
            result = "Unsupported URI scheme",
            success = false,
            toolCallId = "call-1",
        )

        val stored = history.conversation("sess-a")!!.messages.single()
        assertEquals(false, stored.metadata.toolSuccess)
        assertTrue(stored.content.toolResult!!.contains("Unsupported URI scheme"))
    }

    @Test
    fun `sub-agent history stays in the child session and only its summary reaches the parent`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")
        history.createSession(
            workspaceId = null,
            role = AgentRole.EXPLORER,
            parentSessionId = "sess-a",
            sessionId = "sess-child",
        )

        history.recordUser("sess-child", "raw exploration transcript")
        history.assistant("sess-child", "child-m2", "searching every file for auth")
        history.recordSubAgent(
            sessionId = "sess-a",
            childSessionId = "sess-child",
            role = AgentRole.EXPLORER,
            summary = "Authentication lives in AuthRepository.kt.",
            success = true,
        )

        val parentContext = history.modelMessages("sess-a")
        assertFalse(
            parentContext.any { it.content.contains("raw exploration transcript") },
            "the child transcript must not be dumped into the parent context",
        )
        assertTrue(parentContext.any { it.content.contains("Authentication lives in AuthRepository.kt.") })

        // ...while the child keeps its own full history.
        val child = history.conversation("sess-child")!!
        assertEquals(2, child.messages.size)
        assertEquals("sess-a", child.session.parentSessionId)
    }

    // --- streaming, failure and retry --------------------------------------

    @Test
    fun `streaming appends to one message instead of one message per token`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")
        history.startAssistant("sess-a", messageId = "m-2")

        history.streamAssistant("sess-a", "m-2", "Hel")
        history.streamAssistant("sess-a", "m-2", "lo ")
        history.streamAssistant("sess-a", "m-2", "there")

        val conversation = history.conversation("sess-a")!!
        assertEquals(1, conversation.messages.size)
        assertEquals("Hello there", conversation.messages.single().content.text)
        assertEquals(MessageStatus.STREAMING, conversation.messages.single().metadata.status)

        history.completeAssistant("sess-a", "m-2", "Hello there!")
        val completed = history.conversation("sess-a")!!.messages.single()
        assertEquals("Hello there!", completed.content.text)
        assertEquals(MessageStatus.COMPLETED, completed.metadata.status)
    }

    @Test
    fun `a failed generation keeps the user message and records an error`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")

        history.recordUser("sess-a", "Find the authentication code.")
        history.startAssistant("sess-a", messageId = "m-2")
        history.failAssistant("sess-a", "m-2", "The model request timed out.", "TIMEOUT")

        val conversation = history.conversation("sess-a")!!
        assertEquals(2, conversation.messages.size)
        assertEquals(MessageRole.USER, conversation.messages[0].role)
        assertEquals(MessageRole.ERROR, conversation.messages[1].role)
        assertEquals(MessageStatus.ERROR, conversation.messages[1].metadata.status)
        assertEquals("TIMEOUT", conversation.messages[1].content.errorCode)
        assertTrue(conversation.messages[0].content.text.contains("Find the authentication code"))
    }

    @Test
    fun `retrying a turn never duplicates the assistant message`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")
        history.recordUser("sess-a", "Find the authentication code.")

        history.startAssistant("sess-a", messageId = "m-2")
        history.streamAssistant("sess-a", "m-2", "partial")
        history.failAssistant("sess-a", "m-2", "network down", "CONNECTION_FAILED")

        // The user retries; the same assistant id is reused.
        history.startAssistant("sess-a", messageId = "m-2")
        history.completeAssistant("sess-a", "m-2", "It is in AuthRepository.kt.")

        val conversation = history.conversation("sess-a")!!
        assertEquals(2, conversation.messages.size)
        assertEquals(1, conversation.messages.count { it.id == "m-2" })
        assertEquals(MessageRole.ASSISTANT, conversation.messages[1].role)
        assertEquals("It is in AuthRepository.kt.", conversation.messages[1].content.text)
    }

    // --- context reconstruction and budget ---------------------------------

    @Test
    fun `the store stays complete while the model only receives the budget`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")
        repeat(40) { index -> history.recordUser("sess-a", "message-$index " + "x".repeat(150)) }

        val full = history.conversation("sess-a")!!
        assertEquals(40, full.messages.size)

        val selected = ConversationAssembler.select(
            full,
            ContextBudget(maxConversationMessages = 5, maxConversationChars = 10_000),
        )

        assertTrue(selected.size <= 5)
        assertTrue(selected.last().content.text.startsWith("message-39"))
        assertFalse(selected.any { it.content.text.startsWith("message-0 ") })
        // Nothing was deleted from the persistent record.
        assertEquals(40, history.conversation("sess-a")!!.messages.size)
    }

    @Test
    fun `the character budget bounds the assembled conversation`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")
        repeat(30) { index -> history.recordUser("sess-a", "message-$index " + "y".repeat(400)) }

        val selected = ConversationAssembler.select(
            history.conversation("sess-a")!!,
            ContextBudget(maxConversationMessages = 50, maxConversationChars = 1_200),
        )

        assertTrue(selected.isNotEmpty())
        assertTrue(selected.size < 30)
    }

    @Test
    fun `dropped turns are replaced by session memory rather than lost`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")

        history.recordUser("sess-a", "Find the authentication code.")
        history.applyTurn(
            "sess-a",
            "Find the authentication code.",
            AgentResult(
                sessionId = "sess-a",
                status = AgentStatus.COMPLETED,
                summary = "Located the authentication code in AuthRepository.kt.",
                filesInspected = listOf("AuthRepository.kt"),
            ),
        )
        repeat(12) { index -> history.recordUser("sess-a", "follow-up-$index " + "z".repeat(120)) }

        val assembled = ConversationAssembler.modelConversation(
            history.conversation("sess-a")!!,
            ContextBudget(maxConversationMessages = 4, maxConversationChars = 10_000),
        )

        assertEquals(ModelRole.SYSTEM, assembled.first().role)
        assertTrue(assembled.first().content.contains("Session memory"))
        assertTrue(assembled.first().content.contains("AuthRepository.kt"))
        assertEquals(ModelRole.USER, assembled.last().role)
        assertTrue(assembled.last().content.startsWith("follow-up-11"))
    }

    // --- session summary and task state ------------------------------------

    /**
     * The boundary between what a session renders and what a model is told: text that is
     * still arriving is not yet an answer, and must not be sent as one.
     */
    @Test
    fun `a message still being written is stored but is never sent to the model`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")
        history.recordUser("sess-a", "Find the authentication code.")
        history.startAssistant("sess-a", messageId = "m-2")
        history.streamAssistant("sess-a", "m-2", "It is in Auth")

        // The partial text is visible in the session so it can be rendered ...
        val stored = history.conversation("sess-a")!!
        assertEquals(2, stored.messages.size)
        assertEquals(MessageStatus.STREAMING, stored.messages.last().metadata.status)

        // ... but it is not context: the model is not told a turn that has not finished.
        val whileStreaming = history.modelMessages("sess-a")
        assertTrue(whileStreaming.none { it.role == ModelRole.ASSISTANT }, "messages=$whileStreaming")
        assertFalse(whileStreaming.any { it.content.contains("It is in Auth") })
        assertTrue(whileStreaming.any { it.role == ModelRole.USER })

        // Once it settles it is context, as one message and not two.
        history.completeAssistant("sess-a", "m-2", "It is in AuthRepository.kt.")
        val settled = history.modelMessages("sess-a")
        assertEquals(1, settled.count { it.role == ModelRole.ASSISTANT })
        assertTrue(settled.single { it.role == ModelRole.ASSISTANT }.content.contains("AuthRepository.kt"))
    }

    @Test
    fun `a turn updates the summary and the structured task state`() {
        val history = history()
        history.createSession(workspaceId = "ws", sessionId = "sess-a")

        history.applyTurn(
            "sess-a",
            "Fix project file access.",
            AgentResult(
                sessionId = "sess-a",
                status = AgentStatus.FAILED,
                summary = "ReadFileTool could not read the SAF URI.",
                filesInspected = listOf("WorkspaceRuntime.kt"),
                toolActions = listOf(
                    ToolActionRecord(
                        toolName = "ReadFile",
                        success = false,
                        summary = "refused the SAF URI",
                        path = "content://saf/1",
                    ),
                ),
                errors = listOf(AgentError(AgentErrorCode.TOOL_FAILURE, "ReadFileTool fails for SAF URI")),
            ),
        )

        val conversation = history.conversation("sess-a")!!
        assertEquals("Fix project file access.", conversation.summary.currentTask)
        assertTrue(conversation.summary.discoveredFiles.contains("WorkspaceRuntime.kt"))
        assertTrue(conversation.summary.unresolved.any { it.contains("SAF URI") })
        assertTrue(conversation.summary.toolHighlights.any { it.contains("ReadFile") })
        assertEquals(AgentStatus.FAILED, conversation.status)
        assertEquals("Fix project file access.", conversation.taskState.activeTask)
        assertTrue(conversation.taskState.relevantFiles.contains("WorkspaceRuntime.kt"))

        val rendered = conversation.summary.render()
        assertTrue(rendered.contains("Important files"))
        assertTrue(rendered.contains("Auth") || rendered.contains("WorkspaceRuntime.kt"))
    }

    @Test
    fun `the task state records the plan steps and the last error`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")

        history.applyTurn(
            "sess-a",
            "Wire the Context Engine.",
            AgentResult(
                sessionId = "sess-a",
                status = AgentStatus.RUNNING,
                summary = "half way",
                plan = AgentPlan(
                    steps = listOf(
                        AgentStep(index = 0, title = "Inspect the context engine", status = AgentStatus.COMPLETED),
                        AgentStep(index = 1, title = "Add the conversation source", status = AgentStatus.RUNNING),
                        AgentStep(index = 2, title = "Add tests", status = AgentStatus.IDLE),
                    ),
                ),
                errors = listOf(AgentError(AgentErrorCode.CONTEXT_TOO_LARGE, "context budget exceeded")),
            ),
        )

        val state = history.conversation("sess-a")!!.taskState
        assertEquals(listOf("Inspect the context engine"), state.completedSteps)
        assertEquals(listOf("Add the conversation source", "Add tests"), state.pendingSteps)
        assertEquals("Add the conversation source", state.currentStep)
        assertTrue(state.currentPlan!!.contains("Inspect the context engine"))
        assertEquals("context budget exceeded", state.lastError)
    }

    @Test
    fun `an unknown session id is never appended to`() {
        val history = history()

        val result = history.append(
            "nope",
            ConversationMessage(
                id = "m-1",
                sessionId = "nope",
                role = MessageRole.USER,
                content = MessageContent(text = "hello"),
            ),
        )

        assertNull(result)
        assertNull(history.conversation("nope"))
    }

    // --- titles -------------------------------------------------------------

    @Test
    fun `the title comes from the first user message only`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")

        history.recordUser("sess-a", "Why can't the login page authenticate?")
        val titled = history.conversation("sess-a")!!.session.title

        history.recordUser("sess-a", "Actually forget that, look at the refresh token")
        assertEquals(titled, history.conversation("sess-a")!!.session.title)
    }

    @Test
    fun `renaming wins over the derived title`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")
        history.recordUser("sess-a", "Find the authentication code.")

        history.rename("sess-a", "Auth work")
        assertEquals("Auth work", history.conversation("sess-a")!!.session.title)

        history.recordUser("sess-a", "And the refresh token?")
        assertEquals("Auth work", history.conversation("sess-a")!!.session.title)
    }

    @Test
    fun `renaming to blank keeps the current title`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")
        history.rename("sess-a", "Keep me")

        history.rename("sess-a", "   ")

        assertEquals("Keep me", history.conversation("sess-a")!!.session.title)
    }

    // --- delete and clear ---------------------------------------------------

    @Test
    fun `deleting a session leaves the others untouched`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")
        history.createSession(workspaceId = null, sessionId = "sess-b")
        history.recordUser("sess-a", "terminal debugging")
        history.recordUser("sess-b", "UI redesign")

        assertTrue(history.delete("sess-a"))

        assertNull(history.conversation("sess-a"))
        assertNotNull(history.conversation("sess-b"))
        assertEquals(listOf("UI redesign"), history.modelMessages("sess-b").map { it.content })
    }

    @Test
    fun `deleting a session also removes its sub-agent sessions`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")
        history.createSession(
            workspaceId = null,
            role = AgentRole.CODER,
            parentSessionId = "sess-a",
            sessionId = "sess-child",
        )

        history.delete("sess-a")

        assertNull(history.conversation("sess-a"))
        assertNull(history.conversation("sess-child"))
    }

    @Test
    fun `deleting an unknown session is a no-op`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")

        assertFalse(history.delete("nope"))
        assertNotNull(history.conversation("sess-a"))
    }

    @Test
    fun `clearing history keeps the session but empties its memory`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")
        history.recordUser("sess-a", "Find the authentication code.")
        history.applyTurn("sess-a", "Find the authentication code.", completed("done", "AuthRepository.kt"))

        val cleared = history.clearHistory("sess-a")

        assertNotNull(cleared)
        assertTrue(cleared.messages.isEmpty())
        assertTrue(cleared.summary.isEmpty())
        assertEquals(SessionTitle.DEFAULT, cleared.session.title)
        assertEquals(AgentStatus.IDLE, cleared.session.status)
        assertEquals(emptyList(), history.modelMessages("sess-a"))
        assertNotNull(history.conversation("sess-a"))
    }

    // --- secrets ------------------------------------------------------------

    @Test
    fun `credentials are redacted before a message is stored`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")

        history.recordUser("sess-a", "my api_key=sk-live-abcdef123456 is failing")
        history.recordTool(
            sessionId = "sess-a",
            toolName = "RunShell",
            arguments = """{"password":"hunter2"}""",
            result = "token=abc123 done",
            success = true,
        )

        val messages = history.conversation("sess-a")!!.messages
        assertFalse(messages.any { it.content.text.contains("sk-live-abcdef123456") })
        assertFalse(messages.any { (it.content.toolResult ?: "").contains("abc123") })
        assertFalse(messages.any { (it.content.toolArguments ?: "").contains("hunter2") })
        assertTrue(messages[0].content.text.contains("[REDACTED]"))
        assertTrue(messages[0].metadata.redacted)

        // ...and the redaction survives the model boundary too.
        assertFalse(history.modelMessages("sess-a").any { it.content.contains("sk-live-abcdef123456") })
    }

    @Test
    fun `ordinary text is left alone`() {
        val history = history()
        history.createSession(workspaceId = null, sessionId = "sess-a")

        history.recordUser("sess-a", "How does the refresh token logic work?")

        val message = history.conversation("sess-a")!!.messages.single()
        assertEquals("How does the refresh token logic work?", message.content.text)
        assertFalse(message.metadata.redacted)
    }
}
