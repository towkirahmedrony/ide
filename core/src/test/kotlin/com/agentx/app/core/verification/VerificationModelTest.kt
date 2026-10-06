package com.agentx.app.core.verification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The neutral verification vocabulary, and the one place a CI run is mapped onto
 * it. The mapping is asserted directly so the loop, the tool and the runner cannot
 * disagree on whether a build passed.
 */
class VerificationModelTest {

    @Test
    fun `outcome factories expose the four states and their helpers`() {
        val passed = VerificationOutcome.passed("ok")
        assertTrue(passed.passed)
        assertFalse(passed.failed)

        val failed = VerificationOutcome.failed("boom")
        assertTrue(failed.failed)
        assertFalse(failed.passed)

        assertTrue(VerificationOutcome.unavailable("no ci").unavailable)
        assertTrue(VerificationOutcome.cancelled().cancelled)
    }

    @Test
    fun `the failure category comes from the first failing check`() {
        val outcome = VerificationOutcome.failed(
            summary = "build failed",
            checks = listOf(
                VerificationCheck("build", VerificationStatus.PASSED),
                VerificationCheck("tests", VerificationStatus.FAILED, category = VerificationCategory.CI_FAILURE),
            ),
        )

        assertEquals(VerificationCategory.CI_FAILURE, outcome.failureCategory)
    }

    @Test
    fun `an all-passing outcome has no failure category`() {
        val outcome = VerificationOutcome.passed(
            "ok",
            listOf(VerificationCheck("build", VerificationStatus.PASSED)),
        )

        assertNull(outcome.failureCategory)
    }

    private fun run(
        state: CiRunState = CiRunState.COMPLETED,
        conclusion: CiConclusion? = CiConclusion.SUCCESS,
        jobs: List<CiJob> = emptyList(),
    ) = CiRun(
        id = 42,
        name = "ci",
        workflowName = "Build Android Release APK",
        headSha = "abcdef",
        headBranch = "main",
        event = "push",
        state = state,
        conclusion = conclusion,
        jobs = jobs,
    )

    @Test
    fun `a successful run maps to a passing outcome`() {
        val outcome = run().toVerificationOutcome()

        assertTrue(outcome.passed)
        assertTrue(outcome.checks.single().result?.contains("success") == true)
    }

    @Test
    fun `a failing run maps to a failing outcome with the failing job and step`() {
        val job = CiJob(
            id = 7,
            name = "build",
            state = CiRunState.COMPLETED,
            conclusion = CiConclusion.FAILURE,
            steps = listOf(
                CiStep(1, "Checkout", CiConclusion.SUCCESS),
                CiStep(2, "Build Release APK", CiConclusion.FAILURE),
            ),
        )

        val outcome = run(conclusion = CiConclusion.FAILURE, jobs = listOf(job)).toVerificationOutcome("log tail")

        assertTrue(outcome.failed)
        assertEquals(VerificationCategory.CI_FAILURE, outcome.failureCategory)
        val check = outcome.checks.single()
        assertEquals("build", check.name)
        assertEquals("Build Release APK", check.location)
        assertEquals("log tail", check.errorOutput)
    }

    @Test
    fun `an in-progress run is reported as not yet verified`() {
        val outcome = run(state = CiRunState.IN_PROGRESS, conclusion = null).toVerificationOutcome()

        assertTrue(outcome.failed)
        assertEquals(VerificationCategory.CI_FAILURE, outcome.failureCategory)
    }

    @Test
    fun `a timed-out run counts as a failure`() {
        val outcome = run(conclusion = CiConclusion.TIMED_OUT).toVerificationOutcome()

        assertTrue(outcome.failed)
    }
}
