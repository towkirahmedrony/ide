package com.agentx.app.ui.ide.state

import com.agentx.app.ui.ide.data.AgentSession
import com.agentx.app.ui.ide.data.AgentStreamEvent
import com.agentx.app.ui.ide.model.ActivityItemStatus
import com.agentx.app.ui.ide.model.ChatMessageKind
import com.agentx.app.ui.ide.model.MessageState
import com.agentx.app.ui.ide.model.PlanStepStatus
import com.agentx.app.ui.ide.model.PlanStepUiModel
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plan block and the live per-step timing:
 * - a plan is only ever what the Agent Runtime produced, in the runtime's order,
 *   with the runtime's own statuses;
 * - a step in flight ticks, a finished step keeps the duration it finished with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentPlanActivityTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class ScriptedSession(
        private val block: suspend (String, (AgentStreamEvent) -> Unit) -> Unit,
    ) : AgentSession {
        override suspend fun run(
            input: String,
            onEvent: (AgentStreamEvent) -> Unit,
            workspaceId: String?,
            selectedFile: String?,
        ) = block(input, onEvent)
    }

    private fun viewModel(session: AgentSession, now: () -> Long = { 0L }) = AgentViewModel(
        session = session,
        ioDispatcher = UnconfinedTestDispatcher(),
        now = now,
    )

    private fun planSteps(vararg steps: Pair<String, String>): AgentStreamEvent.Plan =
        AgentStreamEvent.Plan(
            steps.mapIndexed { index, (title, status) ->
                AgentStreamEvent.PlanStep(index = index + 1, title = title, status = status)
            },
        )

    private fun assistantPlan(vm: AgentViewModel): List<PlanStepUiModel> =
        vm.uiState.messages.single { it.kind == ChatMessageKind.ASSISTANT }.planSteps

    // ───────────────────────────── Plan status mapping ─────────────────────────────

    @Test
    fun `plan steps map the runtime statuses`() {
        val steps = AgentChatPresentation.planStepsToUi(
            listOf(
                AgentStreamEvent.PlanStep(1, "Inspect project", "COMPLETED"),
                AgentStreamEvent.PlanStep(2, "Implement fix", "RUNNING"),
                AgentStreamEvent.PlanStep(3, "Run tests", "IDLE"),
                AgentStreamEvent.PlanStep(4, "Review changes", "FAILED"),
            ),
        )

        assertEquals(
            listOf(
                PlanStepStatus.DONE,
                PlanStepStatus.ACTIVE,
                PlanStepStatus.PENDING,
                PlanStepStatus.FAILED,
            ),
            steps.map { it.status },
        )
        assertEquals(listOf("Inspect project", "Implement fix", "Run tests", "Review changes"), steps.map { it.title })
    }

    @Test
    fun `an unknown plan status is pending, never done`() {
        // A future runtime state must not be silently rendered as finished work.
        assertEquals(PlanStepStatus.PENDING, AgentChatPresentation.planStatusOf("SOMETHING_NEW"))
        assertEquals(PlanStepStatus.PENDING, AgentChatPresentation.planStatusOf(""))
        assertEquals(PlanStepStatus.PENDING, AgentChatPresentation.planStatusOf("idle"))
    }

    @Test
    fun `plan steps keep the runtime order`() {
        val steps = AgentChatPresentation.planStepsToUi(
            listOf(
                AgentStreamEvent.PlanStep(3, "third", "IDLE"),
                AgentStreamEvent.PlanStep(1, "first", "COMPLETED"),
                AgentStreamEvent.PlanStep(2, "second", "IDLE"),
            ),
        )
        assertEquals(listOf("first", "second", "third"), steps.map { it.title })
    }

    @Test
    fun `no plan produces no rows`() {
        assertTrue(AgentChatPresentation.planStepsToUi(emptyList()).isEmpty())
        assertNull(AgentChatPresentation.activePlanStep(emptyList()))
    }

    @Test
    fun `the collapsed plan shows the step in progress`() {
        val steps = AgentChatPresentation.planStepsToUi(
            listOf(
                AgentStreamEvent.PlanStep(1, "Inspect project", "COMPLETED"),
                AgentStreamEvent.PlanStep(2, "Implement fix", "RUNNING"),
                AgentStreamEvent.PlanStep(3, "Run tests", "IDLE"),
            ),
        )
        assertEquals("Implement fix", AgentChatPresentation.activePlanStep(steps)?.title)
        assertEquals("1 of 3 steps", AgentChatPresentation.planProgressLabel(steps))
    }

    @Test
    fun `with nothing running the plan shows the next pending step`() {
        val steps = AgentChatPresentation.planStepsToUi(
            listOf(
                AgentStreamEvent.PlanStep(1, "Inspect project", "COMPLETED"),
                AgentStreamEvent.PlanStep(2, "Run tests", "IDLE"),
            ),
        )
        assertEquals("Run tests", AgentChatPresentation.activePlanStep(steps)?.title)
    }

    @Test
    fun `a fully finished plan shows its last step`() {
        val steps = AgentChatPresentation.planStepsToUi(
            listOf(
                AgentStreamEvent.PlanStep(1, "Inspect project", "COMPLETED"),
                AgentStreamEvent.PlanStep(2, "Run tests", "COMPLETED"),
            ),
        )
        assertNull(steps.firstOrNull { it.status == PlanStepStatus.ACTIVE })
        assertEquals("Run tests", AgentChatPresentation.activePlanStep(steps)?.title)
        assertEquals("2 of 2 steps", AgentChatPresentation.planProgressLabel(steps))
    }

    // ───────────────────────────── Plan plumbing ─────────────────────────────

    @Test
    fun `the runtime plan is attached to the turn verbatim`() {
        val session = ScriptedSession { _, onEvent ->
            onEvent(planSteps("Inspect project" to "RUNNING", "Run tests" to "IDLE"))
            onEvent(AgentStreamEvent.Completed("done"))
        }
        val vm = viewModel(session)
        vm.onInputChange("fix it")
        vm.send()

        val plan = assistantPlan(vm)
        assertEquals(listOf("Inspect project", "Run tests"), plan.map { it.title })
        assertEquals(listOf(PlanStepStatus.ACTIVE, PlanStepStatus.PENDING), plan.map { it.status })
    }

    @Test
    fun `a later plan replaces the earlier one`() {
        val session = ScriptedSession { _, onEvent ->
            onEvent(planSteps("Inspect project" to "RUNNING"))
            onEvent(planSteps("Inspect project" to "COMPLETED", "Implement fix" to "RUNNING"))
            onEvent(AgentStreamEvent.Completed("done"))
        }
        val vm = viewModel(session)
        vm.onInputChange("fix it")
        vm.send()

        val plan = assistantPlan(vm)
        assertEquals(listOf("Inspect project", "Implement fix"), plan.map { it.title })
        assertEquals(listOf(PlanStepStatus.DONE, PlanStepStatus.ACTIVE), plan.map { it.status })
    }

    @Test
    fun `a turn without a plan shows no plan block`() {
        val session = ScriptedSession { _, onEvent ->
            onEvent(AgentStreamEvent.Activity(com.agentx.app.ui.ide.model.AgentActivity(
                com.agentx.app.ui.ide.model.AgentActivityStatus.THINKING,
                "AI responding",
            )))
            onEvent(AgentStreamEvent.Completed("Hello"))
        }
        val vm = viewModel(session)
        vm.onInputChange("Hi")
        vm.send()

        assertTrue(assistantPlan(vm).isEmpty())
    }

    // ───────────────────────────── Live per-step timing ─────────────────────────────

    @Test
    fun `the step in flight ticks while the turn runs`() {
        var clock = 0L
        val gate = CompletableDeferred<Unit>()
        val session = ScriptedSession { _, onEvent ->
            onEvent(AgentStreamEvent.ToolRequested("read_file", "path=Auth.kt"))
            gate.await()
        }
        val vm = viewModel(session, now = { clock })

        vm.onInputChange("read Auth.kt")
        vm.send()

        val running = vm.uiState.messages
            .single { it.kind == ChatMessageKind.ASSISTANT }
        assertEquals(MessageState.STREAMING, running.state)
        val active = running.activities.single { it.status == ActivityItemStatus.ACTIVE }
        assertNull(active.elapsedMillis)

        clock = 7_000L
        vm.refreshElapsed()

        val ticked = vm.uiState.messages
            .single { it.kind == ChatMessageKind.ASSISTANT }
            .activities
            .single { it.status == ActivityItemStatus.ACTIVE }
        assertEquals(7_000L, ticked.elapsedMillis)
        assertEquals(7_000L, vm.uiState.generation.elapsedMillis)

        gate.complete(Unit)
    }

    @Test
    fun `a finished step keeps the duration it completed with`() {
        var clock = 0L
        val session = ScriptedSession { _, onEvent ->
            onEvent(AgentStreamEvent.ToolRequested("read_file", "path=Auth.kt"))
            clock = 3_000L
            onEvent(AgentStreamEvent.ToolFinished("read_file", true, "read Auth.kt"))
            clock = 9_000L
            onEvent(AgentStreamEvent.Completed("done"))
        }
        val vm = viewModel(session, now = { clock })

        vm.onInputChange("read Auth.kt")
        vm.send()

        val finished = vm.uiState.messages
            .single { it.kind == ChatMessageKind.ASSISTANT }
            .activities
            .single()
        assertEquals(ActivityItemStatus.DONE, finished.status)
        assertEquals(3_000L, finished.elapsedMillis)

        // A later tick must not rewrite history: only a step still in flight ticks.
        vm.refreshElapsed()
        val after = vm.uiState.messages
            .single { it.kind == ChatMessageKind.ASSISTANT }
            .activities
            .single()
        assertEquals(3_000L, after.elapsedMillis)
    }

    @Test
    fun `the running step is still active while the turn is streaming`() {
        val gate = CompletableDeferred<Unit>()
        val session = ScriptedSession { _, onEvent ->
            onEvent(AgentStreamEvent.ToolRequested("search_files", "query=authentication"))
            gate.await()
        }
        val vm = viewModel(session)
        vm.onInputChange("find auth")
        vm.send()

        val activity = vm.uiState.messages
            .single { it.kind == ChatMessageKind.ASSISTANT }
            .activities
            .single()
        assertEquals(ActivityItemStatus.ACTIVE, activity.status)
        assertNotNull(activity.toolName)
        assertNull(activity.outputLines.firstOrNull())

        gate.complete(Unit)
    }
}
