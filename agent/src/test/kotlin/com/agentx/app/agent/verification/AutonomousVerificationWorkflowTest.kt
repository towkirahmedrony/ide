package com.agentx.app.agent.verification

import com.agentx.app.agent.domain.AgentActivity
import com.agentx.app.agent.domain.AgentEvent
import com.agentx.app.agent.domain.AgentResult
import com.agentx.app.agent.domain.AgentRole
import com.agentx.app.agent.domain.AgentStatus
import com.agentx.app.agent.domain.CollectingEventSink
import com.agentx.app.core.verification.VerificationCategory
import com.agentx.app.core.verification.VerificationCheck
import com.agentx.app.core.verification.VerificationOutcome
import com.agentx.app.core.verification.VerificationStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The bounded verify → fix → re-verify workflow. It reuses the existing agent
 * turns (as fakes here) and only sequences them, so these tests are about the
 * sequencing: a failure must trigger a fix, success must end the loop, the retry
 * budget must be respected, and cancellation must stop everything — including any
 * publish.
 */
class AutonomousVerificationWorkflowTest {

    private fun completed(): AgentResult = AgentResult(
        sessionId = "s1",
        status = AgentStatus.COMPLETED,
        summary = "done",
    )

    private fun failedRun(): VerificationOutcome = VerificationOutcome.failed(
        summary = "CI failed",
        checks = listOf(
            VerificationCheck(
                name = "build",
                status = VerificationStatus.FAILED,
                category = VerificationCategory.CI_FAILURE,
                errorOutput = "BUILD FAILED",
            ),
        ),
    )

    private val passed = VerificationOutcome.passed("CI passed")

    private fun sink() = CollectingEventSink()

    @Test
    fun `a passing verification ends the workflow with no fixes`() = runBlocking {
        val sink = sink()
        var fixes = 0

        val result = AutonomousVerificationWorkflow().run(
            sessionId = "s1",
            role = AgentRole.MAIN,
            sink = sink,
            initialTurn = { completed() },
            fixTurn = { fixes += 1; completed() },
            runner = { passed },
        )

        assertIs<VerificationWorkflowResult.Verified>(result)
        assertEquals(0, result.correctionCycles)
        assertEquals(0, fixes)
        assertTrue(activities(sink).contains(AgentActivity.COMPLETED))
    }

    @Test
    fun `a failure triggers a fix and a successful re-verification ends the loop`() = runBlocking {
        val sink = sink()
        var fixes = 0
        val outcomes = ArrayDeque(listOf(failedRun(), passed))

        val result = AutonomousVerificationWorkflow().run(
            sessionId = "s1",
            role = AgentRole.MAIN,
            sink = sink,
            initialTurn = { completed() },
            fixTurn = { diagnosis ->
                fixes += 1
                assertTrue(diagnosis.contains("Build") || diagnosis.contains("build"), diagnosis)
                completed()
            },
            runner = { outcomes.removeFirst() },
        )

        assertIs<VerificationWorkflowResult.Verified>(result)
        assertEquals(1, result.correctionCycles)
        assertEquals(1, fixes)
        val seen = activities(sink)
        assertTrue(seen.contains(AgentActivity.VERIFYING))
        assertTrue(seen.contains(AgentActivity.VERIFICATION_FAILED))
        assertTrue(seen.contains(AgentActivity.FIXING))
        assertTrue(seen.contains(AgentActivity.RE_VERIFYING))
    }

    @Test
    fun `repeated failure stops at the correction budget`() = runBlocking {
        val sink = sink()
        var fixes = 0
        val policy = VerificationPolicy(maxCorrectionCycles = 2)

        val result = AutonomousVerificationWorkflow(policy).run(
            sessionId = "s1",
            role = AgentRole.MAIN,
            sink = sink,
            initialTurn = { completed() },
            fixTurn = { fixes += 1; completed() },
            runner = { failedRun() },
        )

        assertIs<VerificationWorkflowResult.Exhausted>(result)
        assertEquals(2, result.correctionCycles)
        assertEquals(2, fixes, "the budget caps the number of fixes")
        assertTrue(activities(sink).contains(AgentActivity.BLOCKED))
    }

    @Test
    fun `cancellation stops further fixes and never publishes`() = runBlocking {
        val sink = sink()
        var fixes = 0
        var published = 0
        var cancelled = false

        val result = AutonomousVerificationWorkflow().run(
            sessionId = "s1",
            role = AgentRole.MAIN,
            sink = sink,
            initialTurn = { completed() },
            fixTurn = {
                fixes += 1
                cancelled = true
                completed()
            },
            runner = { failedRun() },
            publish = { published += 1; PublishOutcome.Published() },
            onCancelled = { cancelled },
        )

        assertEquals(VerificationWorkflowResult.Cancelled, result)
        assertEquals(1, fixes, "no second fix after cancellation")
        assertEquals(0, published, "nothing is published after cancellation")
        assertTrue(activities(sink).contains(AgentActivity.CANCELLED))
    }

    @Test
    fun `a blocked publish stops the workflow before re-verifying`() = runBlocking {
        val sink = sink()
        var verifyCalls = 0

        val result = AutonomousVerificationWorkflow().run(
            sessionId = "s1",
            role = AgentRole.MAIN,
            sink = sink,
            initialTurn = { completed() },
            fixTurn = { completed() },
            runner = { verifyCalls += 1; failedRun() },
            publish = { PublishOutcome.Blocked("a secret was found", VerificationCategory.SECRET_DETECTED) },
        )

        val blocked = assertIs<VerificationWorkflowResult.Blocked>(result)
        assertEquals(VerificationCategory.SECRET_DETECTED, blocked.category)
        assertEquals(1, verifyCalls, "verification must not run again after a blocked publish")
    }

    @Test
    fun `an unavailable verification is reported, not retried forever`() = runBlocking {
        var fixes = 0

        val result = AutonomousVerificationWorkflow().run(
            sessionId = "s1",
            role = AgentRole.MAIN,
            sink = sink(),
            initialTurn = { completed() },
            fixTurn = { fixes += 1; completed() },
            runner = { VerificationOutcome.unavailable("no CI") },
        )

        assertIs<VerificationWorkflowResult.Unavailable>(result)
        assertEquals(0, fixes)
    }

    @Test
    fun `a failed initial turn blocks the workflow before verification`() = runBlocking {
        var verified = 0

        val result = AutonomousVerificationWorkflow().run(
            sessionId = "s1",
            role = AgentRole.MAIN,
            sink = sink(),
            initialTurn = { AgentResult("s1", AgentStatus.FAILED, "boom") },
            fixTurn = { completed() },
            runner = { verified += 1; passed },
        )

        assertIs<VerificationWorkflowResult.Blocked>(result)
        assertEquals(0, verified)
    }

    @Test
    fun `the diagnosis is safe and bounded`() {
        val outcome = VerificationOutcome.failed(
            "CI failed",
            listOf(
                VerificationCheck(
                    name = "build",
                    status = VerificationStatus.FAILED,
                    category = VerificationCategory.CI_FAILURE,
                    result = "conclusion=failure",
                    location = "Build Release APK",
                    errorOutput = "x".repeat(10_000),
                ),
            ),
        )

        val diagnosis = AutonomousVerificationWorkflow().diagnosisFor(outcome)

        assertTrue(diagnosis.contains("CI_FAILURE"))
        assertTrue(diagnosis.contains("Build Release APK"))
        assertTrue(diagnosis.length < 5_000, "the diagnosis must be bounded")
    }

    private fun activities(sink: CollectingEventSink): List<AgentActivity> =
        sink.events.filterIsInstance<AgentEvent.ActivityChanged>().map { it.activity }
}
