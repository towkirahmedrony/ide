package com.agentx.app.ui.ide.state

import com.agentx.app.ui.ide.data.AgentFailureKind
import com.agentx.app.ui.ide.data.AgentSession
import com.agentx.app.ui.ide.data.AgentSessionInfo
import com.agentx.app.ui.ide.data.AgentStreamEvent
import com.agentx.app.ui.ide.data.PersistedAgentMessage
import com.agentx.app.ui.ide.data.PersistedMessageKind
import com.agentx.app.ui.ide.model.ActivityItemStatus
import com.agentx.app.ui.ide.model.AgentActivity
import com.agentx.app.ui.ide.model.AgentActivityKind
import com.agentx.app.ui.ide.model.AgentActivityStatus
import com.agentx.app.ui.ide.model.AgentTurnOutcome
import com.agentx.app.ui.ide.model.ChatMessageKind
import com.agentx.app.ui.ide.model.GenerationPhase
import com.agentx.app.ui.ide.model.MessageBlock
import com.agentx.app.ui.ide.model.MessageState
import com.agentx.app.ui.ide.model.ToolRunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        session: AgentSession,
        workspaceId: String? = null,
        selectedFile: () -> String? = { null },
        now: () -> Long = { 0L },
        modelId: () -> String? = { null },
    ) = AgentViewModel(
        session = session,
        workspaceId = workspaceId,
        selectedFile = selectedFile,
        now = now,
        ioDispatcher = UnconfinedTestDispatcher(),
        modelId = modelId,
    )

    // ───────────────────────────── Streaming ─────────────────────────────

    @Test
    fun `send streams a reply and marks the turn completed`() {
        val session = RecordingSession { prompt, onEvent ->
            onEvent(AgentStreamEvent.Activity(AgentActivity(AgentActivityStatus.THINKING, "AI responding")))
            onEvent(AgentStreamEvent.Chunk("Hello "))
            onEvent(AgentStreamEvent.Chunk(prompt))
            onEvent(AgentStreamEvent.Completed("Hello $prompt"))
        }
        val vm = viewModel(session)

        vm.onInputChange("world")
        vm.send()

        val state = vm.uiState
        assertFalse(state.running)
        assertEquals("", state.input)
        assertEquals(AgentActivityStatus.COMPLETED, state.activity.status)
        assertEquals(GenerationPhase.COMPLETED, state.generation.phase)
        assertEquals("world", state.messages.single { it.kind == ChatMessageKind.USER }.rawText)
        val assistant = state.messages.single { it.kind == ChatMessageKind.ASSISTANT }
        assertEquals("Hello world", assistant.rawText)
        assertEquals(MessageState.COMPLETE, assistant.state)
        assertNotNull(session.runs.single().sessionId)
    }

    @Test
    fun `structured backend text renders as markdown blocks not raw json`() {
        val code = "class Example {\n    val x = 1\n}"
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.Completed("Here is the file:\n\n```kotlin\n$code\n```\n\nDone."))
        }
        val vm = viewModel(session)

        vm.onInputChange("write it")
        vm.send()

        val assistant = vm.uiState.messages.single { it.kind == ChatMessageKind.ASSISTANT }
        val block = assistant.blocks.filterIsInstance<MessageBlock.Code>().single()
        assertEquals("kotlin", block.language)
        assertTrue(block.code.contains("class Example {"))
        // The response is a markdown paragraph before and after the code block.
        assertTrue(assistant.blocks.filterIsInstance<MessageBlock.Paragraph>().size >= 1)
    }

    @Test
    fun `the elapsed timer uses the real start timestamp and finalises on completion`() {
        var clock = 1_000L
        val gate = CompletableDeferred<Unit>()
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.Chunk("working"))
            gate.await()
            onEvent(AgentStreamEvent.Completed("working!"))
        }
        val vm = viewModel(session, now = { clock })

        vm.onInputChange("go")
        vm.send()

        assertTrue(vm.uiState.running)
        assertEquals(GenerationPhase.THINKING, vm.uiState.generation.phase)
        assertEquals("working", vm.uiState.messages.last { it.kind == ChatMessageKind.ASSISTANT }.rawText)

        clock = 13_400L
        vm.refreshElapsed()
        assertEquals(12_400L, vm.uiState.generation.elapsedMillis)
        assertEquals("12s", AgentChatPresentation.formatElapsedSeconds(vm.uiState.generation.elapsedMillis))

        gate.complete(Unit)

        assertFalse(vm.uiState.running)
        assertEquals(GenerationPhase.COMPLETED, vm.uiState.generation.phase)
        assertEquals(12_400L, vm.uiState.generation.elapsedMillis)
        assertEquals("12.4s", AgentChatPresentation.formatDuration(vm.uiState.generation.elapsedMillis))
    }

    @Test
    fun `stop preserves generated content and marks the turn stopped`() {
        val gate = CompletableDeferred<Unit>()
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.Chunk("partial answer"))
            gate.await()
        }
        val vm = viewModel(session)

        vm.onInputChange("go")
        vm.send()
        vm.stop()
        gate.complete(Unit)

        val state = vm.uiState
        assertFalse(state.running)
        assertEquals(GenerationPhase.STOPPED, state.generation.phase)
        assertEquals("partial answer", state.messages.last { it.kind == ChatMessageKind.ASSISTANT }.rawText)
        assertNotEquals(MessageState.STREAMING, state.messages.last { it.kind == ChatMessageKind.ASSISTANT }.state)
        // The user's message is preserved so the turn can be retried.
        assertEquals("go", state.messages.single { it.kind == ChatMessageKind.USER }.rawText)
    }

    // ───────────────────────────── Failures ─────────────────────────────

    @Test
    fun `a connection failure keeps the prompt and shows a readable error`() {
        val vm = failingViewModel(AgentFailureKind.CONNECTION, "Could not connect")
        vm.onInputChange("ping colab")
        vm.send()

        val state = vm.uiState
        assertFalse(state.running)
        assertEquals("ping colab", state.messages.single { it.kind == ChatMessageKind.USER }.rawText)
        val error = assertNotNull(state.messages.single { it.kind == ChatMessageKind.ERROR }.error)
        assertEquals("Connection failed", error.title)
        assertEquals("Could not connect", error.message)
        assertTrue(error.retryable)
        assertEquals(AgentActivityStatus.CONNECTION_ERROR, state.activity.status)
    }

    @Test
    fun `a timeout shows the timeout state`() {
        val vm = failingViewModel(AgentFailureKind.TIMEOUT, "The model request timed out")
        vm.onInputChange("slow turn")
        vm.send()

        assertEquals(AgentActivityStatus.TIMEOUT, vm.uiState.activity.status)
        assertEquals("Request timed out", vm.uiState.messages.single { it.kind == ChatMessageKind.ERROR }.error?.title)
    }

    @Test
    fun `a missing model is reported without a retry action`() {
        val vm = failingViewModel(AgentFailureKind.NOT_CONFIGURED, "No model is online.")
        vm.onInputChange("hello")
        vm.send()

        val error = assertNotNull(vm.uiState.messages.single { it.kind == ChatMessageKind.ERROR }.error)
        assertEquals("No model connected", error.title)
        assertFalse(error.retryable)
    }

    @Test
    fun `session exceptions become visible errors without dropping the prompt`() {
        val session = RecordingSession { _, _ -> throw IllegalStateException("boom") }
        val vm = viewModel(session)

        vm.onInputChange("keep me")
        vm.send()

        assertEquals("keep me", vm.uiState.messages.single { it.kind == ChatMessageKind.USER }.rawText)
        assertEquals("boom", vm.uiState.messages.single { it.kind == ChatMessageKind.ERROR }.rawText)
        assertEquals(AgentActivityStatus.ERROR, vm.uiState.activity.status)
    }

    // ───────────────────────────── Tools and activity ─────────────────────────────

    @Test
    fun `tool events render as cards and activity on the assistant reply`() {
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.ToolRequested("search_files", "query=SupabaseClient"))
            onEvent(AgentStreamEvent.ToolRunning("search_files"))
            onEvent(AgentStreamEvent.ToolFinished("search_files", true, "1 match in AuthRepository.kt"))
            onEvent(AgentStreamEvent.Completed("Found it"))
        }
        val vm = viewModel(session)

        vm.onInputChange("find auth")
        vm.send()

        val tool = assertNotNull(vm.uiState.messages.single { it.kind == ChatMessageKind.TOOL }.tool)
        assertEquals("SEARCH_FILES", tool.displayName)
        assertEquals(ToolRunStatus.COMPLETED, tool.status)
        assertEquals("query=SupabaseClient", tool.detail)
        assertEquals("1 match in AuthRepository.kt", tool.summary)

        val assistant = vm.uiState.messages.last { it.kind == ChatMessageKind.ASSISTANT }
        assertEquals(1, assistant.activities.size)
        val search = assistant.activities.first()
        assertEquals("\"SupabaseClient\"", search.label)
        assertEquals(AgentActivityKind.SEARCH, search.kind)
        assertEquals(ActivityItemStatus.DONE, search.status)
    }

    @Test
    fun `a terminal tool becomes a terminal row with redacted output`() {
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.ToolRequested("run_command", "command=npm run build"))
            onEvent(AgentStreamEvent.ToolRunning("run_command"))
            onEvent(
                AgentStreamEvent.ToolFinished(
                    "run_command",
                    true,
                    "built ok",
                    output = "vite v5 building…\napi_key=sk-live-abc123\nBUILD SUCCESSFUL",
                ),
            )
            onEvent(AgentStreamEvent.Completed("done"))
        }
        val vm = viewModel(session)
        vm.onInputChange("build")
        vm.send()

        val assistant = vm.uiState.messages.last { it.kind == ChatMessageKind.ASSISTANT }
        val terminal = assistant.activities.single()
        assertEquals(AgentActivityKind.TERMINAL, terminal.kind)
        assertEquals("npm run build", terminal.label)
        assertEquals(ActivityItemStatus.DONE, terminal.status)
        assertTrue(terminal.outputLines.isNotEmpty())
        assertTrue(terminal.outputLines.none { it.contains("sk-live-abc123") })
        assertTrue(terminal.outputLines.any { it.contains("[REDACTED]") })
    }

    @Test
    fun `a failed tool row keeps its failure visible`() {
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.ToolRequested("run_command", "command=./gradlew test"))
            onEvent(AgentStreamEvent.ToolFinished("run_command", false, "Tests failed"))
            onEvent(AgentStreamEvent.Completed("done"))
        }
        val vm = viewModel(session)
        vm.onInputChange("test")
        vm.send()

        val assistant = vm.uiState.messages.last { it.kind == ChatMessageKind.ASSISTANT }
        assertEquals(ActivityItemStatus.FAILED, assistant.activities.single().status)
        // The turn itself is completed; only the step failed.
        assertEquals(AgentTurnOutcome.SUCCESS, vm.uiState.turnOutcome)
    }

    @Test
    fun `sub-agent delegation renders as its own structured row`() {
        var clock = 1_000L
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.SubAgentStarted("EXPLORER", "Explorer", "Inspecting project architecture"))
            clock = 5_400L
            onEvent(AgentStreamEvent.SubAgentFinished("EXPLORER", "Explorer", true, "3 files inspected"))
            onEvent(AgentStreamEvent.Completed("done"))
        }
        val vm = viewModel(session, now = { clock })
        vm.onInputChange("explore")
        vm.send()

        val assistant = vm.uiState.messages.last { it.kind == ChatMessageKind.ASSISTANT }
        val subAgent = assistant.activities.single()
        assertEquals(AgentActivityKind.SUB_AGENT, subAgent.kind)
        assertEquals("EXPLORER", subAgent.role)
        assertEquals("Inspecting project architecture", subAgent.label)
        assertEquals(ActivityItemStatus.DONE, subAgent.status)
        assertEquals(4_400L, subAgent.elapsedMillis)
        assertTrue(subAgent.outputLines.contains("3 files inspected"))
        // The header chip returns to Main once the sub-agent is done.
        assertEquals("Main", vm.uiState.currentAgent)
    }

    @Test
    fun `a failed sub-agent stays failed and returns control to Main`() {
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.SubAgentStarted("CODER", "Coder", "Patching the config"))
            onEvent(AgentStreamEvent.SubAgentFinished("CODER", "Coder", false, "patch rejected"))
            onEvent(AgentStreamEvent.Completed("done"))
        }
        val vm = viewModel(session)
        vm.onInputChange("fix it")
        vm.send()

        val subAgent = vm.uiState.messages.last { it.kind == ChatMessageKind.ASSISTANT }.activities.single()
        assertEquals(ActivityItemStatus.FAILED, subAgent.status)
        assertEquals("Main", vm.uiState.currentAgent)
    }

    @Test
    fun `thinking summaries settle when a tool starts`() {
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.Activity(AgentActivity(AgentActivityStatus.THINKING, "Inspecting the auth flow")))
            onEvent(AgentStreamEvent.ToolRequested("read_file", "path=AuthRepository.kt"))
            onEvent(AgentStreamEvent.ToolFinished("read_file", true, "ok"))
            onEvent(AgentStreamEvent.Completed("done"))
        }
        val vm = viewModel(session)
        vm.onInputChange("go")
        vm.send()

        val assistant = vm.uiState.messages.last { it.kind == ChatMessageKind.ASSISTANT }
        val thinking = assistant.activities.first()
        val read = assistant.activities.last()
        assertEquals("Inspecting the auth flow", thinking.label)
        assertEquals(AgentActivityKind.THINKING, thinking.kind)
        assertEquals(ActivityItemStatus.DONE, thinking.status)
        assertEquals(AgentActivityKind.FILE_READ, read.kind)
        assertEquals("AuthRepository.kt", read.label)
    }

    @Test
    fun `tool arguments are redacted before they reach the UI`() {
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.ToolRequested("run_command", "api_key=sk-live-secret --flag"))
            onEvent(AgentStreamEvent.ToolFinished("run_command", true, "done"))
            onEvent(AgentStreamEvent.Completed("ok"))
        }
        val vm = viewModel(session)
        vm.onInputChange("run")
        vm.send()

        val detail = assertNotNull(vm.uiState.messages.single { it.kind == ChatMessageKind.TOOL }.tool).detail
        assertEquals("api_key=[REDACTED] --flag", detail)
    }

    @Test
    fun `a failed tool is marked failed`() {
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.ToolRequested("run_command", "cmd=./gradlew test"))
            onEvent(AgentStreamEvent.ToolFinished("run_command", false, "Tests failed"))
            onEvent(AgentStreamEvent.Completed("done"))
        }
        val vm = viewModel(session)
        vm.onInputChange("test")
        vm.send()

        val tool = assertNotNull(vm.uiState.messages.single { it.kind == ChatMessageKind.TOOL }.tool)
        assertEquals(ToolRunStatus.FAILED, tool.status)
        val assistant = vm.uiState.messages.last { it.kind == ChatMessageKind.ASSISTANT }
        assertEquals(ActivityItemStatus.FAILED, assistant.activities.single().status)
    }

    // ───────────────────────────── Activity timing and header context ───────

    @Test
    fun `activity rows freeze their own durations from real timestamps`() {
        var clock = 10_000L
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.ToolRequested("read_file", "path=Main.kt"))
            clock = 11_800L
            onEvent(AgentStreamEvent.ToolFinished("read_file", true, "ok"))
            onEvent(AgentStreamEvent.Completed("done"))
        }
        val vm = viewModel(session, now = { clock })
        vm.onInputChange("go")
        vm.send()

        val row = vm.uiState.messages.last { it.kind == ChatMessageKind.ASSISTANT }.activities.single()
        assertEquals(1_800L, row.elapsedMillis)
    }

    @Test
    fun `the header model indicator comes from the model manager`() {
        val session = RecordingSession { _, onEvent -> onEvent(AgentStreamEvent.Completed("ok")) }
        val vm = viewModel(session, modelId = { "qwen2.5-coder" })

        vm.onInputChange("hi")
        vm.send()

        assertEquals("qwen2.5-coder", vm.uiState.modelId)
    }

    @Test
    fun `a missing model indicator stays blank rather than fake`() {
        val session = RecordingSession { _, onEvent -> onEvent(AgentStreamEvent.Completed("ok")) }
        val vm = viewModel(session, modelId = { null })

        vm.onInputChange("hi")
        vm.send()

        assertNull(vm.uiState.modelId)
    }

    // ───────────────────────────── Composer guards ─────────────────────────────

    @Test
    fun `blank input is ignored`() {
        val session = RecordingSession { _, _ -> error("should not run") }
        val vm = viewModel(session)

        vm.onInputChange("   ")
        vm.send()

        assertTrue(vm.uiState.messages.isEmpty())
        assertFalse(vm.uiState.running)
    }

    @Test
    fun `send is ignored while a turn is already running`() {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val session = RecordingSession { _, _ ->
            runs += 1
            gate.await()
        }
        val vm = viewModel(session)

        vm.onInputChange("first")
        vm.send()
        assertTrue(vm.uiState.running)
        vm.onInputChange("second")
        vm.send()

        assertEquals(1, runs)
        assertTrue(vm.uiState.messages.none { it.kind == ChatMessageKind.USER && it.rawText == "second" })
        vm.stop()
        gate.complete(Unit)
        assertFalse(vm.uiState.running)
    }

    @Test
    fun `send forwards the live workspace id and selected file`() {
        var openFile: String? = "src/Main.kt"
        val session = RecordingSession { _, onEvent -> onEvent(AgentStreamEvent.Completed("ok")) }
        val vm = viewModel(session, workspaceId = "saf-project", selectedFile = { openFile })

        vm.onInputChange("What does this project do?")
        vm.send()
        assertEquals("saf-project", session.runs.last().workspaceId)
        assertEquals("src/Main.kt", session.runs.last().selectedFile)

        openFile = "README.md"
        vm.onInputChange("summarise the readme")
        vm.send()
        assertEquals("README.md", session.runs.last().selectedFile)
    }

    // ───────────────────────────── Regenerate / retry / edit ─────────────────────────────

    @Test
    fun `regenerate replaces the reply without duplicating the user message`() {
        var attempt = 0
        val session = RecordingSession { _, onEvent ->
            attempt += 1
            onEvent(AgentStreamEvent.Completed("reply $attempt"))
        }
        val vm = viewModel(session)

        vm.onInputChange("question")
        vm.send()
        val first = vm.uiState.messages.last { it.kind == ChatMessageKind.ASSISTANT }
        assertEquals(1, vm.uiState.messages.count { it.kind == ChatMessageKind.USER })

        vm.regenerate(first.id)

        assertEquals(1, vm.uiState.messages.count { it.kind == ChatMessageKind.USER })
        val second = vm.uiState.messages.last { it.kind == ChatMessageKind.ASSISTANT }
        assertEquals("reply 2", second.rawText)
        assertNotEquals(first.id, second.id)
        assertEquals(1, vm.uiState.messages.count { it.kind == ChatMessageKind.ASSISTANT })
    }

    @Test
    fun `retry reuses the failed turn`() {
        var attempt = 0
        val session = RecordingSession { _, onEvent ->
            attempt += 1
            if (attempt == 1) {
                onEvent(AgentStreamEvent.Failed("Connection to model timed out.", AgentFailureKind.TIMEOUT))
            } else {
                onEvent(AgentStreamEvent.Completed("recovered"))
            }
        }
        val vm = viewModel(session)

        vm.onInputChange("go")
        vm.send()
        val failed = vm.uiState.messages.single { it.kind == ChatMessageKind.ERROR }
        assertEquals(1, vm.uiState.messages.count { it.kind == ChatMessageKind.USER })

        vm.retry(failed.id)

        assertEquals(1, vm.uiState.messages.count { it.kind == ChatMessageKind.USER })
        assertEquals("recovered", vm.uiState.messages.last { it.kind == ChatMessageKind.ASSISTANT }.rawText)
        assertTrue(vm.uiState.messages.none { it.kind == ChatMessageKind.ERROR })
    }

    @Test
    fun `editing a user message resends from that point`() {
        val prompts = mutableListOf<String>()
        val session = RecordingSession { prompt, onEvent ->
            prompts += prompt
            onEvent(AgentStreamEvent.Completed("ok"))
        }
        val vm = viewModel(session)

        vm.onInputChange("first")
        vm.send()
        vm.onInputChange("second")
        vm.send()
        assertEquals(listOf("first", "second"), prompts)

        val firstUser = vm.uiState.messages.first { it.kind == ChatMessageKind.USER }
        vm.editAndResend(firstUser.id, "first revised")

        assertEquals(listOf("first", "second", "first revised"), prompts)
        assertEquals("first revised", vm.uiState.messages.first { it.kind == ChatMessageKind.USER }.rawText)
        // The second turn was dropped with the edit.
        assertTrue(vm.uiState.messages.none { it.kind == ChatMessageKind.USER && it.rawText == "second" })
    }

    // ───────────────────────────── Permissions ─────────────────────────────

    @Test
    fun `permission prompt is shown and resolved from the chat`() {
        val session = PermissionSession()
        val vm = viewModel(session)

        vm.onInputChange("edit config")
        vm.send()

        val prompt = assertNotNull(vm.uiState.pendingPermission)
        assertEquals("write_file", prompt.toolName)
        assertEquals("session-1", prompt.sessionId)
        assertTrue(prompt.detail.contains("config.yaml"))
        assertEquals("WORKSPACE_WRITE", prompt.requiredPermission)
        // A parked turn stays live so Stop is available.
        assertTrue(vm.uiState.running)

        vm.respondToPermission(true)

        val after = vm.uiState
        assertNull(after.pendingPermission)
        assertFalse(after.running)
        assertEquals("Config updated", after.messages.last { it.kind == ChatMessageKind.ASSISTANT }.rawText)
    }

    // ───────────────────────────── Sessions ─────────────────────────────

    @Test
    fun `the sidebar loads persistent sessions newest first and restores the active one`() {
        val session = RecordingSession { _, onEvent -> onEvent(AgentStreamEvent.Completed("ok")) }
        session.seed(
            "s1",
            "Authentication investigation",
            5_000L,
            listOf(
                PersistedAgentMessage("m1", PersistedMessageKind.USER, "fix auth"),
                PersistedAgentMessage(
                    "m2",
                    PersistedMessageKind.ASSISTANT,
                    "Try this:\n\n```kotlin\nclass Auth {}\n```",
                ),
            ),
        )
        session.seed("s2", "Terminal debugging", 9_000L, emptyList())

        val vm = viewModel(session)

        val state = vm.uiState
        assertEquals(2, state.sessions.size)
        assertEquals("Terminal debugging", state.sessions.first().title)
        assertEquals("s2", state.activeSessionId)
        assertTrue(state.sessions.first().active)
    }

    @Test
    fun `opening a session restores its transcript and never mixes sessions`() {
        val session = RecordingSession { _, onEvent -> onEvent(AgentStreamEvent.Completed("ok")) }
        session.seed(
            "s1",
            "Auth",
            5_000L,
            listOf(
                PersistedAgentMessage("m1", PersistedMessageKind.USER, "fix auth"),
                PersistedAgentMessage("m2", PersistedMessageKind.ASSISTANT, "Here is the fix."),
            ),
        )
        session.seed("s2", "Terminal", 9_000L, listOf(PersistedAgentMessage("x1", PersistedMessageKind.USER, "ls")))

        val vm = viewModel(session)
        vm.openSession("s1")

        assertEquals("s1", vm.uiState.activeSessionId)
        assertEquals(listOf("fix auth"), vm.uiState.messages.filter { it.kind == ChatMessageKind.USER }.map { it.rawText })
        assertTrue(vm.uiState.messages.any { it.rawText == "Here is the fix." })

        vm.openSession("s2")
        assertEquals("s2", vm.uiState.activeSessionId)
        assertEquals(listOf("ls"), vm.uiState.messages.filter { it.kind == ChatMessageKind.USER }.map { it.rawText })
    }

    @Test
    fun `restored code blocks render as code rather than escaped text`() {
        val session = RecordingSession { _, onEvent -> onEvent(AgentStreamEvent.Completed("ok")) }
        session.seed(
            "s1",
            "Code",
            1_000L,
            listOf(
                PersistedAgentMessage(
                    "m2",
                    PersistedMessageKind.ASSISTANT,
                    "Example:\n\n```java\nclass Example {\n    int x;\n}\n```",
                ),
            ),
        )
        val vm = viewModel(session)
        vm.openSession("s1")

        val assistant = vm.uiState.messages.single { it.kind == ChatMessageKind.ASSISTANT }
        val code = assistant.blocks.filterIsInstance<MessageBlock.Code>().single()
        assertEquals("java", code.language)
        assertTrue(code.code.contains("class Example"))
    }

    @Test
    fun `renaming and deleting sessions updates the sidebar`() {
        val session = RecordingSession { _, onEvent -> onEvent(AgentStreamEvent.Completed("ok")) }
        session.seed("s1", "Old title", 1_000L, emptyList())
        session.seed("s2", "Keep me", 2_000L, emptyList())

        val vm = viewModel(session)
        vm.renameSession("s1", "New title")
        assertEquals("New title", vm.uiState.sessions.single { it.id == "s1" }.title)

        vm.deleteSession("s1")
        assertTrue(vm.uiState.sessions.none { it.id == "s1" })
        assertTrue(vm.uiState.sessions.any { it.id == "s2" })
    }

    @Test
    fun `deleting the active session starts a fresh one`() {
        val session = RecordingSession { _, onEvent -> onEvent(AgentStreamEvent.Completed("ok")) }
        session.seed("s1", "Only session", 1_000L, listOf(PersistedAgentMessage("m1", PersistedMessageKind.USER, "hi")))

        val vm = viewModel(session)
        assertEquals("s1", vm.uiState.activeSessionId)

        vm.deleteSession("s1")

        assertNotEquals("s1", vm.uiState.activeSessionId)
        assertNotNull(vm.uiState.activeSessionId)
        assertTrue(vm.uiState.messages.isEmpty())
    }

    @Test
    fun `a new session clears the transcript and sends through the new session id`() {
        val session = RecordingSession { _, onEvent -> onEvent(AgentStreamEvent.Completed("ok")) }
        session.seed("s1", "Existing", 1_000L, listOf(PersistedAgentMessage("m1", PersistedMessageKind.USER, "hi")))

        val vm = viewModel(session)
        vm.newSession()
        val fresh = assertNotNull(vm.uiState.activeSessionId)
        assertNotEquals("s1", fresh)
        assertTrue(vm.uiState.messages.isEmpty())

        vm.onInputChange("new task")
        vm.send()
        assertEquals(fresh, session.runs.single().sessionId)
    }

    @Test
    fun `sessions created by a turn are persisted through the port`() {
        val session = RecordingSession { _, onEvent -> onEvent(AgentStreamEvent.Completed("hello")) }
        val vm = viewModel(session)

        vm.onInputChange("hi")
        vm.send()

        val created = assertNotNull(vm.uiState.activeSessionId)
        assertEquals(created, session.runs.single().sessionId)
        assertTrue(session.sessions.containsKey(created))
    }

    private fun failingViewModel(kind: AgentFailureKind, message: String): AgentViewModel {
        val session = RecordingSession { _, onEvent ->
            onEvent(AgentStreamEvent.Failed(message, kind))
        }
        return viewModel(session)
    }

    /** An in-memory [AgentSession] with a real session store and recorded runs. */
    private class RecordingSession(
        private val block: suspend (String, (AgentStreamEvent) -> Unit) -> Unit = { _, onEvent ->
            onEvent(AgentStreamEvent.Completed("ok"))
        },
    ) : AgentSession {

        data class RunCall(
            val sessionId: String?,
            val input: String,
            val workspaceId: String?,
            val selectedFile: String?,
        )

        val runs = mutableListOf<RunCall>()
        val sessions = LinkedHashMap<String, MutableList<PersistedAgentMessage>>()
        private val titles = LinkedHashMap<String, String>()
        private val updated = LinkedHashMap<String, Long>()
        private var active: String? = null
        private var counter = 0

        fun seed(id: String, title: String, updatedAt: Long, messages: List<PersistedAgentMessage>) {
            sessions[id] = messages.toMutableList()
            titles[id] = title
            updated[id] = updatedAt
        }

        override suspend fun run(
            input: String,
            onEvent: (AgentStreamEvent) -> Unit,
            workspaceId: String?,
            selectedFile: String?,
        ) {
            runs += RunCall(null, input, workspaceId, selectedFile)
            block(input, onEvent)
        }

        override suspend fun runInSession(
            sessionId: String,
            input: String,
            onEvent: (AgentStreamEvent) -> Unit,
            workspaceId: String?,
            selectedFile: String?,
        ) {
            runs += RunCall(sessionId, input, workspaceId, selectedFile)
            block(input, onEvent)
        }

        override suspend fun listSessions(): List<AgentSessionInfo> =
            sessions.keys.sortedByDescending { updated[it] ?: 0L }.map { id ->
                AgentSessionInfo(
                    id = id,
                    title = titles[id] ?: "New session",
                    updatedAtMillis = updated[id] ?: 0L,
                    messageCount = sessions[id]?.size ?: 0,
                    active = id == active,
                )
            }

        override suspend fun activeSessionId(): String? = active

        override suspend fun createSession(): AgentSessionInfo {
            counter += 1
            val id = "created-$counter"
            sessions[id] = mutableListOf()
            titles[id] = "New session"
            updated[id] = 0L
            active = id
            return AgentSessionInfo(id, "New session", 0L, 0, true)
        }

        override suspend fun restoreSession(sessionId: String): List<PersistedAgentMessage> {
            active = sessionId
            return sessions[sessionId].orEmpty().toList()
        }

        override suspend fun renameSession(sessionId: String, title: String): Boolean {
            if (!sessions.containsKey(sessionId)) return false
            titles[sessionId] = title
            return true
        }

        override suspend fun deleteSession(sessionId: String): Boolean {
            val removed = sessions.remove(sessionId) != null
            titles.remove(sessionId)
            updated.remove(sessionId)
            if (active == sessionId) active = null
            return removed
        }
    }

    /** A session that parks on a permission request, then continues when resolved. */
    private class PermissionSession : AgentSession {
        override suspend fun run(
            input: String,
            onEvent: (AgentStreamEvent) -> Unit,
            workspaceId: String?,
            selectedFile: String?,
        ) {
            onEvent(AgentStreamEvent.Activity(AgentActivity(AgentActivityStatus.THINKING, "AI responding")))
            onEvent(
                AgentStreamEvent.PermissionRequired(
                    toolName = "write_file",
                    reason = "Tool 'write_file' requires approval",
                    sessionId = "session-1",
                    toolCallId = "call-1",
                    detail = "path=config.yaml",
                    requiredPermission = "WORKSPACE_WRITE",
                ),
            )
        }

        override suspend fun resolvePermission(
            sessionId: String,
            approved: Boolean,
            onEvent: (AgentStreamEvent) -> Unit,
        ) {
            assertEquals("session-1", sessionId)
            assertTrue(approved)
            onEvent(AgentStreamEvent.ToolRequested("write_file", "path=config.yaml"))
            onEvent(AgentStreamEvent.ToolFinished("write_file", true, "Wrote config.yaml"))
            onEvent(AgentStreamEvent.Completed("Config updated"))
        }
    }
}
